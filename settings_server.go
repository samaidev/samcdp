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
	mux.HandleFunc("/api/lang", handleSetLang)
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

// openSettings 打开设置页面（服务已常驻时直接复用）
func openSettings() {
	url, err := settingsURL()
	if err != nil {
		return
	}
	openBrowser(url)
}

// settingsURL 返回设置页地址（未启动则启动；app 启动时常驻本地 127.0.0.1）
func settingsURL() (string, error) {
	if settingsLn != nil {
		return fmt.Sprintf("http://%s", settingsLn.Addr().String()), nil
	}
	return startSettingsServer()
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
	oldKey := cfg.GetAitunKey()
	cfg.SetAitunKey(req.AitunKey)
	msg := t("settings.saved")
	// v1.4.9: Key 变更（含清空）→ 必须确定性切换模式：
	//   有 Key → 子域名；无 Key → 全新临时代理地址。
	// 原逻辑只在运行中时重启隧道，且从不清理身份：无 key 时客户端会静默
	// 复用 <23h 的旧 auth_token（可能是其他 aitun 应用写入的注册身份），
	// 导致“设置里没填 Key，地址却是注册子域名（Subdomain-only）”的困惑。
	// 现在只要 Key 变了就清掉整个身份目录（client_id/auth_token），
	// 运行中则立即按新 Key 重启隧道
	if req.AitunKey != oldKey {
		wasRunning := svc.IsRunning()
		if wasRunning {
			tunnel.Stop()
		}
		ClearAitunIdentity()
		if wasRunning {
			if err := tunnel.Start(svc.GetCDPPort(), req.AitunKey); err != nil {
				writeJSON2(w, 200, map[string]interface{}{"ok": true, "msg": t("settings.saved") + t("sp.jsSavedButTunnel") + err.Error()})
				return
			}
			msg = t("settings.saved") + t("sp.jsSavedTunnel")
		}
	}
	writeJSON2(w, 200, map[string]interface{}{"ok": true, "msg": msg})
}

// handleSetLang v1.4.0: 切换界面语言（立即持久化，页面 JS 调用后自行 reload）
func handleSetLang(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", 405)
		return
	}
	var req struct {
		Lang string `json:"lang"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON2(w, 400, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	if req.Lang == "zh" {
		setLang(LangZH)
	} else {
		setLang(LangEN)
	}
	cfg.Lang = string(currentLang)
	cfg.save()
	writeJSON2(w, 200, map[string]interface{}{"ok": true, "lang": string(currentLang)})
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	tunnelURL := tunnel.GetPublicURL()
	profiles := svc.GetAllProfileStatus()
	writeJSON2(w, 200, map[string]interface{}{
		"running":        svc.IsRunning(),
		"token":          svc.GetToken(),
		"version":        appVersion,
		"base_url":       tunnelURL,
		"tunnel_running": tunnel.IsRunning(),
		"tunnel_error":   tunnel.GetError(),
		"lan_url":        svc.GetLocalAddress(),
		"local_url":      svc.GetCDPAddress(),
		"aitun_key":      cfg.GetAitunKey(),
		// v1.4.9: 模式透明化 —— 身份目录路径（页面提示清理用）+
		// 是否正在复用持久化身份（解释地址为何不是全新随机地址）
		"aitun_home":   aitunHomeDir(),
		"reused_token": tunnel.IsReusingToken(),
		"profiles":     profiles,
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
