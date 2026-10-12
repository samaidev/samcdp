package main

// proxy.go — CDP 管理代理：单隧道入口 + 多 Chrome CDP 实例管理 API
//
// 架构:
//   远程 → https://mos.t.aitun.cc (单条 aitun 隧道, 映射到端口 9333)
//              ↓
//         CDP 管理代理 (端口 9333)
//           ├── /            → API 文档页面 (HTML)
//           ├── /api/...     → 管理 API (token 鉴权)
//           └── /<profile>/... → CDP 透传 (json/*, devtools/*)
//
// 鉴权: ?token=xxx 查询参数 或 Authorization: Bearer xxx 头

import (
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"sync"
)

// ---------------------------------------------------------------------------
// 数据结构
// ---------------------------------------------------------------------------

// CDPProxy CDP 管理代理
type CDPProxy struct {
	mu     sync.RWMutex
	routes map[string]string // profile name → target URL (如 http://127.0.0.1:9334)
	ln     net.Listener
}

var cdpProxy = &CDPProxy{
	routes: make(map[string]string),
}

// ProfileInfo profile 状态信息（API 返回用）
type ProfileInfo struct {
	Name    string `json:"name"`
	Port    int    `json:"port"`
	Running bool   `json:"running"`
	Tabs    int    `json:"tabs"`
}

// TabInfo CDP 标签页信息
type TabInfo struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	Type        string `json:"type"`
	WebsocketID string `json:"ws_id"`
}

// ---------------------------------------------------------------------------
// 代理生命周期
// ---------------------------------------------------------------------------

// Start 启动代理服务
func (p *CDPProxy) Start(listenAddr string) (string, error) {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return "", err
	}
	p.ln = ln

	srv := &http.Server{Handler: http.HandlerFunc(p.handle)}
	go srv.Serve(ln)
	return "http://" + ln.Addr().String(), nil
}

// Stop 停止代理
func (p *CDPProxy) Stop() {
	if p.ln != nil {
		p.ln.Close()
		p.ln = nil
	}
}

// SetRoute 设置路由
func (p *CDPProxy) SetRoute(profileName, targetURL string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.routes[profileName] = targetURL
}

// RemoveRoute 删除路由
func (p *CDPProxy) RemoveRoute(profileName string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.routes, profileName)
}

// ---------------------------------------------------------------------------
// 请求入口
// ---------------------------------------------------------------------------

func (p *CDPProxy) handle(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	// 根路径: API 文档页面
	if path == "/" || path == "" {
		p.handleDocsPage(w, r)
		return
	}

	// 管理 API: /api/*
	if strings.HasPrefix(path, "/api/") {
		p.handleAPI(w, r)
		return
	}

	// CDP 透传: /<profile>/json/* 或 /<profile>/devtools/*
	parts := strings.SplitN(strings.TrimPrefix(path, "/"), "/", 2)
	profileName := parts[0]

	p.mu.RLock()
	targetURL, ok := p.routes[profileName]
	p.mu.RUnlock()

	if !ok {
		writeAPIError(w, 404, "profile '"+profileName+"' not found")
		return
	}

	// 重写路径
	if len(parts) > 1 {
		r.URL.Path = "/" + parts[1]
	} else {
		r.URL.Path = "/"
	}

	// WebSocket 或 HTTP 透传
	if isWebSocketUpgrade(r) {
		p.proxyWebSocket(w, r, targetURL)
		return
	}
	p.proxyHTTP(w, r, targetURL)
}

// ---------------------------------------------------------------------------
// 管理 API: /api/*
// ---------------------------------------------------------------------------

func (p *CDPProxy) handleAPI(w http.ResponseWriter, r *http.Request) {
	// 鉴权
	if !checkToken(r) {
		writeAPIError(w, 401, "unauthorized: missing or invalid token")
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api")

	switch {
	// GET /api/profiles — 列出所有 profile
	case path == "/profiles" && r.Method == "GET":
		p.apiListProfiles(w, r)

	// POST /api/profiles — 创建新 profile
	case path == "/profiles" && r.Method == "POST":
		p.apiCreateProfile(w, r)

	// DELETE /api/profiles/<name> — 删除 profile
	case strings.HasPrefix(path, "/profiles/") && r.Method == "DELETE":
		name := strings.TrimPrefix(path, "/profiles/")
		p.apiDeleteProfile(w, r, name)

	// POST /api/profiles/<name>/start — 启动 Chrome
	case strings.HasPrefix(path, "/profiles/") && strings.HasSuffix(path, "/start") && r.Method == "POST":
		name := strings.TrimPrefix(strings.TrimSuffix(path, "/start"), "/profiles/")
		p.apiStartProfile(w, r, name)

	// POST /api/profiles/<name>/stop — 停止 Chrome
	case strings.HasPrefix(path, "/profiles/") && strings.HasSuffix(path, "/stop") && r.Method == "POST":
		name := strings.TrimPrefix(strings.TrimSuffix(path, "/stop"), "/profiles/")
		p.apiStopProfile(w, r, name)

	// GET /api/profiles/<name>/tabs — 列出标签页
	case strings.HasPrefix(path, "/profiles/") && strings.HasSuffix(path, "/tabs") && r.Method == "GET":
		name := strings.TrimPrefix(strings.TrimSuffix(path, "/tabs"), "/profiles/")
		p.apiListTabs(w, r, name)

	// POST /api/profiles/<name>/tabs/new?url=xxx — 打开新标签页
	case strings.HasPrefix(path, "/profiles/") && strings.HasSuffix(path, "/tabs/new") && r.Method == "POST":
		name := strings.TrimPrefix(strings.TrimSuffix(path, "/tabs/new"), "/profiles/")
		p.apiNewTab(w, r, name)

	// DELETE /api/profiles/<name>/tabs/<id> — 关闭标签页
	case strings.HasPrefix(path, "/profiles/") && strings.Contains(path, "/tabs/") && r.Method == "DELETE":
		parts := strings.Split(strings.TrimPrefix(strings.TrimPrefix(path, "/profiles/"), "/"), "/tabs/")
		if len(parts) == 2 {
			p.apiCloseTab(w, r, parts[0], parts[1])
		} else {
			writeAPIError(w, 400, "bad tab path")
		}

	default:
		writeAPIError(w, 404, "unknown API endpoint: "+r.Method+" "+r.URL.Path)
	}
}

// --- API 处理函数 ---

func (p *CDPProxy) apiListProfiles(w http.ResponseWriter, r *http.Request) {
	profiles := cfg.GetProfiles()
	result := make([]ProfileInfo, 0, len(profiles))
	for _, prof := range profiles {
		info := ProfileInfo{
			Name: prof.Name,
			Port: prof.Port,
		}
		p.mu.RLock()
		_, info.Running = p.routes[prof.Name]
		p.mu.RUnlock()
		if info.Running {
			info.Tabs = p.countTabs(prof.Name)
		}
		result = append(result, info)
	}
	writeJSON(w, 200, map[string]interface{}{
		"ok":       true,
		"profiles": result,
	})
}

func (p *CDPProxy) apiCreateProfile(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeAPIError(w, 400, "bad request body: "+err.Error())
		return
	}
	if req.Name == "" {
		writeAPIError(w, 400, "name is required")
		return
	}
	// 检查重名
	for _, p2 := range cfg.GetProfiles() {
		if p2.Name == req.Name {
			writeAPIError(w, 409, "profile '"+req.Name+"' already exists")
			return
		}
	}
	newProf := cfg.AddProfile(req.Name)
	writeJSON(w, 201, map[string]interface{}{
		"ok":      true,
		"profile": ProfileInfo{Name: newProf.Name, Port: newProf.Port, Running: false, Tabs: 0},
	})
}

func (p *CDPProxy) apiDeleteProfile(w http.ResponseWriter, r *http.Request, name string) {
	// 停止 Chrome 实例
	if svc.IsRunning() {
		svc.stopProfileByName(name)
	}
	cfg.RemoveProfile(name)
	writeJSON(w, 200, map[string]interface{}{"ok": true})
}

func (p *CDPProxy) apiStartProfile(w http.ResponseWriter, r *http.Request, name string) {
	if !svc.IsRunning() {
		writeAPIError(w, 409, "service not running, start service first")
		return
	}
	// 必填参数 duration（分钟）：超时自动关闭 Chrome，防止窗口堆积
	durationStr := r.URL.Query().Get("duration")
	if durationStr == "" {
		writeAPIError(w, 400, "missing required parameter 'duration' (minutes): specify how long Chrome should stay open, e.g. duration=30. Chrome will auto-close after this time to prevent window accumulation.")
		return
	}
	duration, err := strconv.Atoi(durationStr)
	if err != nil || duration <= 0 {
		writeAPIError(w, 400, "invalid 'duration' parameter: must be a positive integer (minutes), e.g. duration=30")
		return
	}
	err = svc.startProfileByName(name, duration)
	if err != nil {
		writeAPIError(w, 500, "start failed: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"ok":       true,
		"message":  "profile '" + name + "' started",
		"duration": duration,
	})
}

func (p *CDPProxy) apiStopProfile(w http.ResponseWriter, r *http.Request, name string) {
	err := svc.stopProfileByName(name)
	if err != nil {
		writeAPIError(w, 500, "stop failed: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"ok":      true,
		"message": "profile '" + name + "' stopped",
	})
}

func (p *CDPProxy) apiListTabs(w http.ResponseWriter, r *http.Request, name string) {
	p.mu.RLock()
	targetURL, ok := p.routes[name]
	p.mu.RUnlock()
	if !ok {
		writeAPIError(w, 404, "profile '"+name+"' not running")
		return
	}
	// 代理到 Chrome 的 /json/list
	tabs := fetchCDPTabs(targetURL + "/json/list")
	result := make([]TabInfo, 0, len(tabs))
	for _, tab := range tabs {
		result = append(result, TabInfo{
			ID:          str(tab, "id"),
			Title:       str(tab, "title"),
			URL:         str(tab, "url"),
			Type:        str(tab, "type"),
			WebsocketID: str(tab, "webSocketDebuggerUrl"),
		})
	}
	writeJSON(w, 200, map[string]interface{}{
		"ok":   true,
		"tabs": result,
	})
}

func (p *CDPProxy) apiNewTab(w http.ResponseWriter, r *http.Request, name string) {
	p.mu.RLock()
	targetURL, ok := p.routes[name]
	p.mu.RUnlock()
	if !ok {
		writeAPIError(w, 404, "profile '"+name+"' not running")
		return
	}
	targetURL = strings.TrimSuffix(targetURL, "/")

	tabURL := r.URL.Query().Get("url")
	if tabURL == "" {
		var body struct {
			URL string `json:"url"`
		}
		json.NewDecoder(r.Body).Decode(&body)
		tabURL = body.URL
	}
	if tabURL == "" {
		writeAPIError(w, 400, "url parameter is required")
		return
	}

	// Chrome 154 要求 PUT
	req, _ := http.NewRequest("PUT", targetURL+"/json/new?"+url.QueryEscape(tabURL), nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		writeAPIError(w, 502, "chrome error: "+err.Error())
		return
	}
	defer resp.Body.Close()
	var tab map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&tab)
	writeJSON(w, 200, map[string]interface{}{
		"ok":  true,
		"tab": tab,
	})
}

func (p *CDPProxy) apiCloseTab(w http.ResponseWriter, r *http.Request, name, tabID string) {
	p.mu.RLock()
	targetURL, ok := p.routes[name]
	p.mu.RUnlock()
	if !ok {
		writeAPIError(w, 404, "profile '"+name+"' not running")
		return
	}
	targetURL = strings.TrimSuffix(targetURL, "/")
	req, _ := http.NewRequest("GET", targetURL+"/json/close/"+tabID, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		writeAPIError(w, 502, "chrome error: "+err.Error())
		return
	}
	defer resp.Body.Close()
	writeJSON(w, 200, map[string]interface{}{
		"ok":      true,
		"message": "tab " + tabID + " closed",
	})
}

// ---------------------------------------------------------------------------
// CDP 透传
// ---------------------------------------------------------------------------

func (p *CDPProxy) proxyHTTP(w http.ResponseWriter, r *http.Request, targetURL string) {
	target, err := url.Parse(targetURL)
	if err != nil {
		writeAPIError(w, 500, "bad target url")
		return
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.ServeHTTP(w, r)
}

func (p *CDPProxy) proxyWebSocket(w http.ResponseWriter, r *http.Request, targetURL string) {
	target, err := url.Parse(targetURL)
	if err != nil {
		writeAPIError(w, 500, "bad target url")
		return
	}
	backendConn, err := net.Dial("tcp", target.Host)
	if err != nil {
		writeAPIError(w, 502, "backend unreachable")
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		writeAPIError(w, 500, "hijack not supported")
		backendConn.Close()
		return
	}
	clientConn, clientBuf, err := hj.Hijack()
	if err != nil {
		backendConn.Close()
		return
	}
	targetPath := r.URL.Path
	if r.URL.RawQuery != "" {
		targetPath += "?" + r.URL.RawQuery
	}
	reqLine := r.Method + " " + targetPath + " " + r.Proto + "\r\n"
	backendConn.Write([]byte(reqLine))
	r.Header.Write(backendConn)
	backendConn.Write([]byte("\r\n"))
	go func() {
		defer backendConn.Close()
		defer clientConn.Close()
		io.Copy(backendConn, clientBuf)
		io.Copy(backendConn, clientConn)
	}()
	go func() {
		defer backendConn.Close()
		defer clientConn.Close()
		io.Copy(clientConn, backendConn)
	}()
}

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

func isWebSocketUpgrade(r *http.Request) bool {
	return strings.ToLower(r.Header.Get("Upgrade")) == "websocket"
}

func checkToken(r *http.Request) bool {
	token := cfg.GetAuthToken()
	if token == "" {
		return true
	}
	// ?token=xxx
	if r.URL.Query().Get("token") == token {
		return true
	}
	// Authorization: Bearer xxx
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") && auth[7:] == token {
		return true
	}
	return false
}

func (p *CDPProxy) countTabs(profileName string) int {
	p.mu.RLock()
	targetURL, ok := p.routes[profileName]
	p.mu.RUnlock()
	if !ok {
		return 0
	}
	tabs := fetchCDPTabs(targetURL + "/json/list")
	return len(tabs)
}

func fetchCDPTabs(url string) []map[string]interface{} {
	resp, err := http.Get(url)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var tabs []map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&tabs)
	return tabs
}

func str(m map[string]interface{}, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func writeAPIError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]interface{}{"ok": false, "error": msg})
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func init() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
}
