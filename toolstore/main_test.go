package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCatalogLifecycle(t *testing.T) {
	s, e := openStore(":memory:")
	if e != nil {
		t.Fatal(e)
	}
	defer s.db.Close()
	h := s.routes()
	call := func(method, path, body string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(method, path, strings.NewReader(body)))
		return w
	}
	body := `{"nombre":"estado_fedora","descripcion":"Consulta memoria y estado Fedora","target_host":"fedora.vpn","command_template":"/opt/agent-tools/status.sh","parameters":{"type":"object","properties":{},"required":[]}}`
	if w := call("POST", "/api/tools", body); w.Code != 201 {
		t.Fatalf("create %d %s", w.Code, w.Body)
	}
	if w := call("POST", "/api/tools", body); w.Code != 409 {
		t.Fatal("duplicate", w.Code)
	}
	if w := call("POST", "/tools/search", `{"query":"memoria Fedora","top_k":3}`); w.Code != 200 || !strings.Contains(w.Body.String(), "estado_fedora") {
		t.Fatal(w.Code, w.Body)
	}
	if w := call("POST", "/tools/search", `{"query":"zzzzzzz"}`); w.Body.String() != "{\"tools\":[]}\n" {
		t.Fatal(w.Body)
	}
	if w := call("POST", "/tools/search", `{"query":"fedora","top_k":4}`); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := call("POST", "/api/tools", body+body); w.Code != 400 {
		t.Fatal("trailing JSON accepted")
	}
	updated := strings.ReplaceAll(body, "Consulta memoria y estado Fedora", "Consulta disco y estado Fedora")
	if w := call("PUT", "/api/tools/1", updated); w.Code != 200 || !strings.Contains(w.Body.String(), "Consulta disco") {
		t.Fatal("update failed", w.Code, w.Body)
	}
	if w := call("PUT", "/api/tools/999", updated); w.Code != 404 {
		t.Fatal("missing update", w.Code)
	}
	if w := call("PUT", "/api/tools/1", strings.ReplaceAll(updated, "estado_fedora", "Nombre inválido")); w.Code != 400 {
		t.Fatal("invalid update accepted", w.Code)
	}
	if w := call("GET", "/api/tools", ""); !strings.Contains(w.Body.String(), "estado_fedora") || strings.Contains(w.Body.String(), "Nombre inválido") {
		t.Fatal("invalid update mutated catalog", w.Body)
	}
	if w := call("DELETE", "/api/tools/1", ""); w.Code != 204 {
		t.Fatal(w.Code)
	}
	if w := call("DELETE", "/api/tools/1", ""); w.Code != 404 {
		t.Fatal(w.Code)
	}
	ts, e := s.list(context.Background())
	if e != nil || len(ts) != 0 {
		t.Fatal(ts, e)
	}
}
func TestRankingAndAuth(t *testing.T) {
	ts := []Tool{{Nombre: "video", Descripcion: "Descarga video youtube"}, {Nombre: "memoria", Descripcion: "memoria servidor linux"}}
	r := rank("memoria servidor", ts, 3)
	if len(r) != 1 || r[0].Name != "memoria" {
		t.Fatal(r)
	}
	s, e := openStore(":memory:")
	if e != nil {
		t.Fatal(e)
	}
	defer s.db.Close()
	s.token = "test"
	w := httptest.NewRecorder()
	s.routes().ServeHTTP(w, httptest.NewRequest("GET", "/api/tools", nil))
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	req := httptest.NewRequest("GET", "/api/tools", nil)
	req.Header.Set("Authorization", "Bearer test")
	w = httptest.NewRecorder()
	s.routes().ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	var tool Tool
	json.Unmarshal([]byte(`{"nombre":"bad","descripcion":"test","target_host":"host","command_template":"s {missing}","parameters":{"type":"object","properties":{}}}`), &tool)
	if validate(tool) == nil {
		t.Fatal("missing placeholder accepted")
	}
}
