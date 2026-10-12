package main

// util.go — 通用工具函数

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func timeNow() time.Time {
	return time.Now()
}

func getAppDataDir() string {
	base := os.Getenv("APPDATA")
	if base == "" {
		base, _ = os.UserConfigDir()
	}
	return filepath.Join(base, "cdpclient")
}

func getLocalAppData() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base, _ = os.UserConfigDir()
	}
	return base
}

// regQueryChrome 从 Windows 注册表查找 Chrome 路径
func regQueryChrome() string {
	if runtime.GOOS != "windows" {
		return ""
	}
	return winRegQueryString(`SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe`, "")
}

// httpClient 带超时的 HTTP 客户端
var httpClient = &http.Client{Timeout: 15 * time.Second}

// fetchJSON 发送 GET 请求并解析 JSON
func fetchJSON(url string, v interface{}) error {
	resp, err := httpClient.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	return json.Unmarshal(body, v)
}
