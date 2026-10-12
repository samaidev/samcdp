package main

// settings_server.go — 设置页面 HTTP 服务器（多 profile API）

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
)

var settingsLn net.Listener

// startSettingsServer 启动设置页面 HTTP 服务器 (随机端口)
func startSettingsServer() (string, error) {
	stopSettingsServer()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	settingsLn = ln

	mux := http.NewServeMux()
	mux.HandleFunc("/", handleSettingsPage)
	mux.HandleFunc("/api/settings", handleSaveSettings)
	mux.HandleFunc("/api/status", handleStatus)
	mux.HandleFunc("/api/regenerate-token", handleRegenerateToken)
	mux.HandleFunc("/api/profile/add", handleAddProfile)
	mux.HandleFunc("/api/profile/delete", handleDeleteProfile)
	mux.HandleFunc("/api/profile/toggle", handleToggleProfile)

	go func() {
		http.Serve(ln, mux)
	}()

	return fmt.Sprintf("http://%s", ln.Addr().String()), nil
}

// stopSettingsServer 关闭设置页面服务器
func stopSettingsServer() {
	if settingsLn != nil {
		settingsLn.Close()
		settingsLn = nil
	}
}

// openSettings 打开设置页面
func openSettings() {
	url, err := startSettingsServer()
	if err != nil {
		return
	}
	openBrowser(url)
}

func handleSettingsPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(renderSettingsHTML()))
}

func handleSaveSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		AitunKey string `json:"aitun_key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	cfg.SetAitunKey(req.AitunKey)
	writeJSON2(w, 200, map[string]interface{}{"ok": true, "msg": t("settings.saved")})
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	tunnelURL := tunnel.GetPublicURL()
	profiles := svc.GetAllProfileStatus()
	writeJSON2(w, 200, map[string]interface{}{
		"running":   svc.IsRunning(),
		"token":     svc.GetToken(),
		"base_url":  tunnelURL,
		"profiles":  profiles,
	})
}

func handleRegenerateToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	newToken := cfg.RegenerateAuthToken()
	writeJSON2(w, 200, map[string]interface{}{
		"ok":    true,
		"token": newToken,
	})
}

func handleAddProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	if req.Name == "" {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": "name required"})
		return
	}
	// 检查重名
	for _, p := range cfg.GetProfiles() {
		if p.Name == req.Name {
			writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": "name exists"})
			return
		}
	}
	p := cfg.AddProfile(req.Name)
	writeJSON2(w, 200, map[string]interface{}{"ok": true, "profile": p})
}

func handleDeleteProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	// 不允许删除最后一个 profile
	if len(cfg.GetProfiles()) <= 1 {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": "at least one profile required"})
		return
	}
	// 如果服务在运行，先停止该 profile 的实例
	if svc.IsRunning() {
		svc.mu.Lock()
		if inst, ok := svc.instances[req.Name]; ok {
			inst.mu.Lock()
			inst.running = false
			close(inst.stopCh)
			if inst.chromeCmd != nil && inst.chromeCmd.Process != nil {
				inst.chromeCmd.Process.Kill()
			}
			inst.mu.Unlock()
			delete(svc.instances, req.Name)
		}
		svc.mu.Unlock()
		cdpProxy.RemoveRoute(req.Name)
	}
	cfg.RemoveProfile(req.Name)
	writeJSON2(w, 200, map[string]interface{}{"ok": true})
}

func handleToggleProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		Name    string `json:"name"`
		Enabled bool   `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	cfg.SetProfileEnabled(req.Name, req.Enabled)
	writeJSON2(w, 200, map[string]interface{}{"ok": true})
}

func writeJSON2(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}
