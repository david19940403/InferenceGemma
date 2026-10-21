package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestExpandedSearchAndInternalNames(t *testing.T) {
	s, err := openStore(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	h := s.routes()
	for i := 0; i < 10; i++ {
		body := fmt.Sprintf(`{"name":"status_%d","description":"windows status memory","target_host":"pc","command_template":"status.ps1","parameters":{"type":"object","properties":{}}}`, i)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("POST", "/api/tools", strings.NewReader(body)))
		if w.Code != 201 {
			t.Fatalf("create: %d %s", w.Code, w.Body)
		}
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("POST", "/tools/search", strings.NewReader(`{"query":"windows","top_k":16}`)))
	var result struct {
		Tools []json.RawMessage `json:"tools"`
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &result) != nil || len(result.Tools) != 10 {
		t.Fatalf("expanded: %d %s", w.Code, w.Body)
	}
	for _, name := range []string{"search_tools", "finish_transaction"} {
		body := fmt.Sprintf(`{"name":"%s","description":"reserved","target_host":"pc","command_template":"status.ps1","parameters":{"type":"object","properties":{}}}`, name)
		w = httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("POST", "/api/tools", strings.NewReader(body)))
		if w.Code != 400 {
			t.Fatalf("reserved accepted: %s %d", name, w.Code)
		}
	}
}

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
	if w := call("POST", "/tools/search", `{"query":"fedora","top_k":17}`); w.Code != 400 {
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

func TestLocalAdminSession(t *testing.T) {
	s, err := openStore(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	s.token = "private-api-token"
	s.adminCode = "one-use-local-code"
	h := s.routes()
	remote := httptest.NewRequest("GET", "/auth/bootstrap?code=one-use-local-code", nil)
	remote.RemoteAddr = "192.0.2.10:1234"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, remote)
	if w.Code != 404 {
		t.Fatal("remote bootstrap accepted", w.Code)
	}
	req := httptest.NewRequest("GET", "/auth/bootstrap?code=one-use-local-code", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	w = httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 303 || w.Header().Get("Location") != "/" {
		t.Fatal("bootstrap failed", w.Code)
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatal("session cookie missing protection")
	}
	cookie := cookies[0]
	replay := httptest.NewRecorder()
	h.ServeHTTP(replay, req)
	if replay.Code != 404 {
		t.Fatal("bootstrap code reused")
	}
	check := func(header string, expected int) {
		r := httptest.NewRequest("GET", "/api/tools", nil)
		r.AddCookie(cookie)
		if header != "" {
			r.Header.Set("Authorization", header)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != expected {
			t.Fatalf("session/header auth got %d want %d", w.Code, expected)
		}
	}
	check("", 200)
	check("Bearer wrong-token", 401)
	evil := httptest.NewRequest("POST", "/auth/logout", nil)
	evil.AddCookie(cookie)
	evil.Header.Set("Origin", "https://other.invalid")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, evil)
	if w.Code != 403 {
		t.Fatal("cross-origin logout allowed")
	}
	check("", 200)
	logout := httptest.NewRequest("POST", "/auth/logout", nil)
	logout.AddCookie(cookie)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, logout)
	if w.Code != 204 {
		t.Fatal("logout failed")
	}
	check("", 401)
}

func TestDashboardAssets(t *testing.T) {
	s, err := openStore(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	h := s.routes()
	for _, path := range []string{"/", "/assets/styles.css", "/assets/app.js"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != 200 || w.Body.Len() == 0 {
			t.Fatal("missing asset", path, w.Code)
		}
	}
	for _, path := range []string{"/assets/missing.js", "/assets/main.go"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != 404 {
			t.Fatal("unexpected asset", path, w.Code)
		}
	}
}
