package main

// aitun_fetch.go — aitun 客户端自动获取（v1.4.6）
// 发布物是单个裸可执行文件，不捆绑 aitun 客户端；首次运行或隧道启动前
// 自动从 aitun.cc 下载当前平台的客户端。部署目录优先 exe 同目录（部署标准
// 位置），不可写（如 Program Files）则退回用户配置目录。

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

var (
	aitunFetchMu    sync.Mutex
	aitunFetchTried time.Time // 节流：失败后 10 分钟内不重复下载
)

// aitunDownloadURL 返回当前平台的 aitun 客户端下载地址（aitun.cc 官方分发，已验证）：
//   windows/amd64 → aitun-client-windows-amd64.exe (16,078,336 B)
//   linux/amd64   → aitun-client-linux-amd64       (10,256,101 B)
//   darwin/amd64  → aitun-client-darwin-amd64      (10,444,816 B)
//   darwin/arm64  → aitun-client-darwin-arm64      ( 9,773,170 B)
func aitunDownloadURL() string {
	osName := runtime.GOOS
	if osName != "windows" && osName != "darwin" && osName != "linux" {
		return ""
	}
	ext := ""
	if osName == "windows" {
		ext = ".exe"
	}
	return fmt.Sprintf("https://aitun.cc/downloads/aitun-client-%s-%s%s", osName, runtime.GOARCH, ext)
}

// aitunWritableDir 返回可写入的部署目录：exe 同目录优先，失败退回 configDir()
func aitunWritableDir() string {
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		if dirWritable(dir) {
			return dir
		}
	}
	dir := configDir()
	os.MkdirAll(dir, 0755)
	return dir
}

func dirWritable(dir string) bool {
	p := filepath.Join(dir, ".samcdp-write-test")
	f, err := os.Create(p)
	if err != nil {
		return false
	}
	f.Close()
	os.Remove(p)
	return true
}

// fetchAitunBinary 下载并安装 aitun 客户端，返回其路径；已存在则直接返回现有路径
func fetchAitunBinary() (string, error) {
	aitunFetchMu.Lock()
	defer aitunFetchMu.Unlock()

	if p := findAitunBinary(); p != "" {
		return p, nil
	}
	url := aitunDownloadURL()
	if url == "" {
		return "", fmt.Errorf("unsupported platform %s/%s", runtime.GOOS, runtime.GOARCH)
	}
	if !aitunFetchTried.IsZero() && time.Since(aitunFetchTried) < 10*time.Minute {
		return "", fmt.Errorf("aitun client download recently failed, will retry later")
	}
	aitunFetchTried = time.Now()

	dir := aitunWritableDir()
	name := "aitun-client"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	dst := filepath.Join(dir, name)
	tmp := dst + ".download"

	debugLog(fmt.Sprintf("[aitun-fetch] downloading %s -> %s", url, dst))
	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return "", fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("http %d (%s)", resp.StatusCode, url)
	}
	f, err := os.Create(tmp)
	if err != nil {
		return "", fmt.Errorf("create temp file: %w", err)
	}
	n, err := io.Copy(f, resp.Body)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("save: %w", err)
	}
	if n < 1024*1024 {
		os.Remove(tmp)
		return "", fmt.Errorf("downloaded file too small (%d B)", n)
	}
	if runtime.GOOS != "windows" {
		os.Chmod(tmp, 0755)
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("install: %w", err)
	}
	debugLog(fmt.Sprintf("[aitun-fetch] client installed: %s (%d B)", dst, n))
	return dst, nil
}

// ensureAitunBinary 进程启动时调用：客户端缺失则自动下载（失败仅告警不阻塞，
// 隧道启动时还会重试并在失败时把原因写入设置页错误行）
func ensureAitunBinary() {
	if findAitunBinary() != "" {
		return
	}
	if _, err := fetchAitunBinary(); err != nil {
		fmt.Fprintf(os.Stderr, "[cdpclient] aitun client fetch failed: %v (tunnel start will retry)\n", err)
	}
}
