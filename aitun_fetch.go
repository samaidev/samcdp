package main

// aitun_fetch.go — aitun 客户端获取（v1.4.7 内嵌优先）
// v1.4.7: aitun 客户端直接打包进发布物（windows/linux/darwin 各自内嵌对应
// 平台二进制，见 aitun_embed_*.go + aitunbin/），启动时提取到部署目录——
// 零网络依赖，断网/防火墙环境也能起隧道。解析顺序：内嵌提取 > 磁盘查找
// （exe 同目录/PATH/常见路径）> aitun.cc 下载（v1.4.6 兼容路径，仅内嵌
// 提取失败时使用）。提取目录优先 exe 同目录（部署标准位置），不可写
// （如 Program Files）则退回用户配置目录。

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
//
//	windows/amd64 → aitun-client-windows-amd64.exe (16,078,336 B)
//	linux/amd64   → aitun-client-linux-amd64       (10,256,101 B)
//	darwin/amd64  → aitun-client-darwin-amd64      (10,444,816 B)
//	darwin/arm64  → aitun-client-darwin-arm64      ( 9,773,170 B)
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

// extractEmbeddedAitun v1.4.7: 把内嵌客户端写到部署目录（原子：先写 .extract
// 再 rename），返回路径。目标已存在且尺寸与内嵌一致 → 直接复用（零拷贝快路径）；
// 尺寸不同（升级带新客户端）→ 覆盖。rename 失败（如旧客户端正在运行、Windows
// 文件锁）由调用方兜底：退回 findAitunBinary 磁盘查找。
func extractEmbeddedAitun() (string, error) {
	dir := aitunWritableDir()
	name := "aitun-client"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	dst := filepath.Join(dir, name)
	if st, err := os.Stat(dst); err == nil && st.Size() == int64(len(aitunEmbedded)) {
		return dst, nil // already extracted (same build) — reuse
	}
	tmp := dst + ".extract"
	if err := os.WriteFile(tmp, aitunEmbedded, 0755); err != nil {
		return "", fmt.Errorf("extract to %s: %w", tmp, err)
	}
	if runtime.GOOS != "windows" {
		os.Chmod(tmp, 0755) // WriteFile mode is umask-filtered; be explicit
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("install %s: %w", dst, err)
	}
	debugLog(fmt.Sprintf("[aitun-embed] client extracted: %s (%d B)", dst, len(aitunEmbedded)))
	return dst, nil
}

// resolveAitunBinary v1.4.7: 客户端解析统一入口，优先级：
//  1. 内嵌提取（随发布物分发，与 app 版本一致，离线可用）
//  2. 磁盘查找 findAitunBinary（exe 同目录 / PATH / 常见路径）
//  3. aitun.cc 下载 fetchAitunBinary（v1.4.6 兼容，仅内嵌提取失败时触达）
func resolveAitunBinary() (string, error) {
	if len(aitunEmbedded) > 0 {
		if p, err := extractEmbeddedAitun(); err == nil {
			return p, nil
		} else {
			debugLog(fmt.Sprintf("[aitun-embed] extraction failed: %v — falling back to disk/download", err))
		}
	}
	if p := findAitunBinary(); p != "" {
		return p, nil
	}
	return fetchAitunBinary()
}

// fetchAitunBinary 下载并安装 aitun 客户端，返回其路径（v1.4.7: 仅作为
// resolveAitunBinary 的最后手段；自身不再先查磁盘，由 resolve 统一排序）
func fetchAitunBinary() (string, error) {
	aitunFetchMu.Lock()
	defer aitunFetchMu.Unlock()

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

// ensureAitunBinary 进程启动时调用：v1.4.7 走 resolveAitunBinary（内嵌优先，
// 首次运行把客户端落盘；失败仅告警不阻塞，隧道启动时还会重试并在失败时
// 把原因写入设置页错误行）
func ensureAitunBinary() {
	if _, err := resolveAitunBinary(); err != nil {
		fmt.Fprintf(os.Stderr, "[cdpclient] aitun client unavailable: %v (tunnel start will retry)\n", err)
	}
}
