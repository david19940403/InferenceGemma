package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"html/template"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	_ "modernc.org/sqlite"
)

type Tool struct {
	ID              int64          `json:"id,omitempty"`
	Name            string         `json:"name"`
	Description     string         `json:"description"`
	TargetHost      string         `json:"target_host"`
	CommandTemplate string         `json:"command_template"`
	Parameters      map[string]any `json:"parameters"`
	User            string         `json:"user,omitempty"`
	Port            int            `json:"port,omitempty"`
	Nombre          string         `json:"nombre,omitempty"`
	Descripcion     string         `json:"descripcion,omitempty"`
}

type formattedTool struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Transport   map[string]any `json:"transport"`
	Parameters  map[string]any `json:"parameters"`
}

type server struct {
	db           *sql.DB
	page         *template.Template
	token        string
	adminMu      sync.Mutex
	adminCode    string
	adminSession string
	adminExpires time.Time
}

//go:embed dashboard.html web/*
var assets embed.FS

func main() {
	loadEnvFile()
	if isWindowsService(os.Args[1:]) {
		if err := runWindowsService(); err != nil {
			log.Fatal(err)
		}
		return
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := runServer(ctx); err != nil {
		log.Fatal(err)
	}
}

func runServer(ctx context.Context) error {
	s, err := openStore(env("TOOLSTORE_DB", "./store.db"))
	if err != nil {
		return err
	}
	defer s.db.Close()
	s.token = os.Getenv("TOOLSTORE_TOKEN")
	s.adminCode = os.Getenv("TOOLSTORE_ADMIN_CODE")
	addr := env("TOOLSTORE_ADDR", "127.0.0.1:8000")
	log.Printf("Tool Store escuchando en %s", addr)
	srv := &http.Server{Addr: addr, Handler: requestLog(s.routes()), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(c)
	}()
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return err
	}
	return nil
}

func loadEnvFile() {
	path := os.Getenv("TOOLSTORE_ENV_FILE")
	if path == "" {
		if executable, err := os.Executable(); err == nil {
			path = filepath.Join(filepath.Dir(executable), "toolstore.env")
		} else {
			path = "toolstore.env"
		}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok || strings.TrimSpace(key) == "" || os.Getenv(strings.TrimSpace(key)) != "" {
			continue
		}
		value = strings.TrimSpace(value)
		if len(value) >= 2 && ((value[0] == '\'' && value[len(value)-1] == '\'') || (value[0] == '"' && value[len(value)-1] == '"')) {
			value = value[1 : len(value)-1]
		}
		_ = os.Setenv(strings.TrimSpace(key), value)
	}
}

func openStore(dsn string) (*server, error) {
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err = db.Exec("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;"); err != nil {
		db.Close()
		return nil, err
	}
	if err := initDB(db); err != nil {
		db.Close()
		return nil, err
	}
	return &server{db: db, page: template.Must(template.ParseFS(assets, "dashboard.html"))}, nil
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.index)
	mux.HandleFunc("GET /auth/bootstrap", s.bootstrap)
	mux.HandleFunc("POST /auth/logout", s.logout)
	mux.HandleFunc("GET /assets/{file}", func(w http.ResponseWriter, r *http.Request) {
		name := r.PathValue("file")
		mime := map[string]string{"app.js": "text/javascript; charset=utf-8", "styles.css": "text/css; charset=utf-8"}[name]
		if mime == "" {
			http.NotFound(w, r)
			return
		}
		content, err := assets.ReadFile("web/" + name)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Cache-Control", "no-cache")
		w.Write(content)
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if s.db.PingContext(r.Context()) != nil {
			problem(w, 503, errors.New("SQLite no disponible"))
			return
		}
		respond(w, 200, map[string]string{"status": "ok", "index": "tf-idf-cosine"})
	})
	mux.HandleFunc("GET /api/tools", s.listHandler)
	mux.HandleFunc("POST /api/tools", s.create)
	mux.HandleFunc("PUT /api/tools/{id}", s.update)
	mux.HandleFunc("DELETE /api/tools/{id}", s.delete)
	mux.HandleFunc("POST /tools/search", s.search)
	return s.authorize(mux)
}

func (s *server) authorize(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if strings.HasPrefix(r.URL.Path, "/api/") || r.URL.Path == "/tools/search" {
			if s.token != "" && !s.authorized(r) {
				problem(w, 401, errors.New("no autorizado"))
				return
			}
			if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
				problem(w, 403, errors.New("origen no permitido"))
				return
			}
		}
		if r.URL.Path == "/auth/logout" {
			if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host && origin != "https://"+r.Host {
				problem(w, 403, errors.New("origen no permitido"))
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (s *server) authorized(r *http.Request) bool {
	if header := r.Header.Get("Authorization"); header != "" {
		return subtle.ConstantTimeCompare([]byte(header), []byte("Bearer "+s.token)) == 1
	}
	cookie, err := r.Cookie("toolstore_admin")
	if err != nil {
		return false
	}
	s.adminMu.Lock()
	defer s.adminMu.Unlock()
	return s.adminSession != "" && time.Now().Before(s.adminExpires) && subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(s.adminSession)) == 1
}

// Optional one-use link issued by the local operator. The API token never enters the URL.
func (s *server) bootstrap(w http.ResponseWriter, r *http.Request) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		http.NotFound(w, r)
		return
	}
	s.adminMu.Lock()
	defer s.adminMu.Unlock()
	code := r.URL.Query().Get("code")
	if s.adminCode == "" || subtle.ConstantTimeCompare([]byte(code), []byte(s.adminCode)) != 1 {
		http.NotFound(w, r)
		return
	}
	random := make([]byte, 32)
	if _, err := rand.Read(random); err != nil {
		problem(w, 500, errors.New("no se pudo iniciar la sesión"))
		return
	}
	s.adminCode = ""
	s.adminSession = hex.EncodeToString(random)
	s.adminExpires = time.Now().Add(8 * time.Hour)
	http.SetCookie(w, &http.Cookie{Name: "toolstore_admin", Value: s.adminSession, Path: "/", HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteStrictMode, MaxAge: 8 * 60 * 60})
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	s.adminMu.Lock()
	defer s.adminMu.Unlock()
	if cookie, err := r.Cookie("toolstore_admin"); err == nil && subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(s.adminSession)) == 1 {
		s.adminSession = ""
		s.adminExpires = time.Time{}
	}
	http.SetCookie(w, &http.Cookie{Name: "toolstore_admin", Value: "", Path: "/", HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	w.WriteHeader(http.StatusNoContent)
}

func initDB(db *sql.DB) error {
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS tools (
		id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL,
		description TEXT NOT NULL, target_host TEXT NOT NULL,
		command_template TEXT NOT NULL, schema_json TEXT NOT NULL,
		ssh_user TEXT NOT NULL DEFAULT 'root', ssh_port INTEGER NOT NULL DEFAULT 22)`)
	return err
}

func (s *server) index(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	page, _ := assets.ReadFile("web/index.html")
	w.Write(page)
}

func (s *server) listHandler(w http.ResponseWriter, r *http.Request) {
	tools, err := s.list(r.Context())
	if err != nil {
		problem(w, 500, err)
		return
	}
	respond(w, 200, tools)
}

func (s *server) list(_ context.Context) ([]Tool, error) { return s.allTools() }

func (s *server) create(w http.ResponseWriter, r *http.Request) {
	var t Tool
	if err := decode(w, r, &t); err != nil {
		problem(w, 400, err)
		return
	}
	t.normalize()
	if err := validate(t); err != nil {
		problem(w, 400, err)
		return
	}
	schema, _ := json.Marshal(t.Parameters)
	if t.User == "" {
		t.User = "root"
	}
	if t.Port == 0 {
		t.Port = 22
	}
	result, err := s.db.Exec(`INSERT INTO tools(name,description,target_host,command_template,schema_json,ssh_user,ssh_port) VALUES(?,?,?,?,?,?,?)`,
		t.Name, t.Description, t.TargetHost, t.CommandTemplate, schema, t.User, t.Port)
	if err != nil {
		problem(w, 409, errors.New("el nombre ya existe o los datos son inválidos"))
		return
	}
	t.ID, _ = result.LastInsertId()
	respond(w, 201, t)
}

func (s *server) update(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id < 1 {
		problem(w, 400, errors.New("id inválido"))
		return
	}
	var t Tool
	if err = decode(w, r, &t); err != nil {
		problem(w, 400, err)
		return
	}
	t.normalize()
	if err = validate(t); err != nil {
		problem(w, 400, err)
		return
	}
	if t.User == "" {
		t.User = "root"
	}
	if t.Port == 0 {
		t.Port = 22
	}
	schema, _ := json.Marshal(t.Parameters)
	result, err := s.db.ExecContext(r.Context(), `UPDATE tools SET name=?,description=?,target_host=?,command_template=?,schema_json=?,ssh_user=?,ssh_port=? WHERE id=?`, t.Name, t.Description, t.TargetHost, t.CommandTemplate, string(schema), t.User, t.Port, id)
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			problem(w, 409, errors.New("ya existe una acción con ese nombre"))
		} else {
			problem(w, 500, errors.New("no se pudo guardar la acción"))
		}
		return
	}
	n, err := result.RowsAffected()
	if err != nil {
		problem(w, 500, errors.New("no se pudo comprobar la actualización"))
		return
	}
	if n == 0 {
		problem(w, 404, errors.New("acción no encontrada"))
		return
	}
	t.ID = id
	respond(w, 200, t)
}

func (s *server) delete(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id < 1 {
		problem(w, 400, errors.New("id inválido"))
		return
	}
	result, err := s.db.Exec("DELETE FROM tools WHERE id=?", id)
	if err != nil {
		problem(w, 500, err)
		return
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		problem(w, 404, errors.New("tool no encontrada"))
		return
	}
	w.WriteHeader(204)
}

func (s *server) search(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Query string `json:"query"`
		TopK  int    `json:"top_k"`
	}
	if err := decode(w, r, &req); err != nil {
		problem(w, 400, err)
		return
	}
	if strings.TrimSpace(req.Query) == "" || len(req.Query) > 2000 {
		problem(w, 400, errors.New("query vacío o demasiado largo"))
		return
	}
	if req.TopK == 0 {
		req.TopK = 3
	}
	if req.TopK < 1 || req.TopK > 3 {
		problem(w, 400, errors.New("top_k debe estar entre 1 y 3"))
		return
	}
	tools, err := s.allTools()
	if err != nil {
		problem(w, 500, err)
		return
	}
	out := rank(req.Query, tools, req.TopK)
	respond(w, 200, map[string]any{"tools": out})
}

func (s *server) allTools() ([]Tool, error) {
	rows, err := s.db.Query(`SELECT id,name,description,target_host,command_template,schema_json,ssh_user,ssh_port FROM tools ORDER BY id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	tools := []Tool{}
	for rows.Next() {
		var t Tool
		var schema []byte
		if err := rows.Scan(&t.ID, &t.Name, &t.Description, &t.TargetHost, &t.CommandTemplate, &schema, &t.User, &t.Port); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(schema, &t.Parameters); err != nil {
			return nil, err
		}
		t.normalize()
		tools = append(tools, t)
	}
	return tools, rows.Err()
}

func format(t Tool) formattedTool {
	t.normalize()
	return formattedTool{t.Name, t.Description, map[string]any{"type": "ssh", "target_host": t.TargetHost, "command_template": t.CommandTemplate, "user": t.User, "port": t.Port}, t.Parameters}
}

func rank(query string, tools []Tool, topK int) []formattedTool {
	type ranked struct {
		tool  Tool
		score float64
	}
	matches := []ranked{}
	terms := vectorTerms(query)
	docs := make([]map[string]float64, len(tools))
	df := map[string]float64{}
	for i, t := range tools {
		t.normalize()
		docs[i] = vectorTerms(t.Name + " " + t.Description + " " + t.TargetHost)
		for word := range docs[i] {
			df[word]++
		}
	}
	for i, t := range tools {
		dot, a, b := 0.0, 0.0, 0.0
		for word, n := range terms {
			idf := math.Log(1 + float64(len(tools))/(1+df[word]))
			v := n * idf
			a += v * v
			dot += v * docs[i][word] * idf
		}
		for word, n := range docs[i] {
			idf := math.Log(1 + float64(len(tools))/(1+df[word]))
			b += n * n * idf * idf
		}
		if dot > 0 && a*b > 0 {
			matches = append(matches, ranked{t, dot / math.Sqrt(a*b)})
		}
	}
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].score > matches[j].score })
	if len(matches) > topK {
		matches = matches[:topK]
	}
	out := make([]formattedTool, 0, len(matches))
	for _, m := range matches {
		out = append(out, format(m.tool))
	}
	return out
}

func vectorTerms(text string) map[string]float64 {
	text = strings.Map(func(r rune) rune {
		switch r {
		case 'á':
			return 'a'
		case 'é':
			return 'e'
		case 'í':
			return 'i'
		case 'ó':
			return 'o'
		case 'ú':
			return 'u'
		}
		return r
	}, strings.ToLower(text))
	out := map[string]float64{}
	for _, word := range strings.FieldsFunc(text, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }) {
		if len(word) > 2 {
			out[word]++
		}
	}
	return out
}

func (t *Tool) normalize() {
	if t.Name == "" {
		t.Name = t.Nombre
	}
	if t.Description == "" {
		t.Description = t.Descripcion
	}
	if t.Nombre == "" {
		t.Nombre = t.Name
	}
	if t.Descripcion == "" {
		t.Descripcion = t.Description
	}
}
func validateTool(t Tool) error {
	t.normalize()
	if strings.TrimSpace(t.Name) == "" || strings.TrimSpace(t.Description) == "" || strings.TrimSpace(t.TargetHost) == "" || strings.TrimSpace(t.CommandTemplate) == "" || t.Parameters == nil {
		return errors.New("name, description, target_host, command_template y parameters son obligatorios")
	}
	return nil
}

func validate(t Tool) error {
	t.normalize()
	if err := validateTool(t); err != nil {
		return err
	}
	nameRE := regexp.MustCompile(`^[a-z][a-z0-9_]{0,63}$`)
	if !nameRE.MatchString(t.Name) || t.Name == "finish_transaction" || !regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$`).MatchString(t.TargetHost) || len(t.Description) > 1000 || len(t.CommandTemplate) > 2000 {
		return errors.New("nombre, host, descripción o plantilla inválidos")
	}
	if t.Parameters["type"] != "object" {
		return errors.New("el esquema debe ser un objeto")
	}
	props, ok := t.Parameters["properties"].(map[string]any)
	if !ok {
		return errors.New("falta properties")
	}
	for name, raw := range props {
		p, ok := raw.(map[string]any)
		if !ok || p["type"] != "string" || !nameRE.MatchString(name) {
			return errors.New("los parámetros deben ser strings con nombres válidos")
		}
	}
	if req, exists := t.Parameters["required"]; exists {
		a, ok := req.([]any)
		if !ok {
			return errors.New("required debe ser una lista")
		}
		for _, v := range a {
			n, ok := v.(string)
			if !ok || props[n] == nil {
				return errors.New("required referencia un parámetro inexistente")
			}
		}
	}
	for _, m := range regexp.MustCompile(`\{([A-Za-z_][A-Za-z0-9_]*)\}`).FindAllStringSubmatch(t.CommandTemplate, -1) {
		if _, ok := props[m[1]]; !ok {
			return errors.New("placeholder sin declarar")
		}
	}
	return nil
}
func tokenize(v string) map[string]struct{} {
	out := map[string]struct{}{}
	for _, word := range strings.FieldsFunc(strings.ToLower(v), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }) {
		if len([]rune(word)) > 1 {
			out[word] = struct{}{}
		}
	}
	return out
}
func decode(w http.ResponseWriter, r *http.Request, dst any) error {
	defer r.Body.Close()
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	d.DisallowUnknownFields()
	if err := d.Decode(dst); err != nil {
		return err
	}
	if d.Decode(&struct{}{}) != io.EOF {
		return errors.New("se permite un solo objeto JSON")
	}
	return nil
}
func respond(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func problem(w http.ResponseWriter, status int, err error) {
	respond(w, status, map[string]string{"error": err.Error()})
}
func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
func requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("%s %s", r.Method, r.URL.Path)
		next.ServeHTTP(w, r)
	})
}
