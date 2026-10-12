package main

// tunnel.go — aitun 隧道管理（单隧道，映射到 CDP 路由代理）

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
)

// Tunnel 管理单个 aitun 隧道进程
type Tunnel struct {
	mu        sync.Mutex
	cmd       *exec.Cmd
	publicURL string
	running   bool
	stopCh    chan struct{}
}

var tunnel = &Tunnel{}

// Start 启动 aitun 隧道，映射 localPort
func (t *Tunnel) Start(localPort int, aitunKey string) error {
	t.mu.Lock()
	defer t.mu.Unlock()

	if t.running {
		return fmt.Errorf("tunnel already running")
	}

	bin := findAitunBinary()
	debugLog(fmt.Sprintf("[tunnel] aitun binary: %s", bin))
	if bin == "" {
		return fmt.Errorf("aitun binary not found")
	}

	args := []string{
		"-s", "aitun.cc:6639",
		"-p", fmt.Sprintf("%d", localPort),
	}
	if aitunKey != "" {
		args = append(args, "-k", aitunKey)
	}
	debugLog(fmt.Sprintf("[tunnel] starting aitun: %s %v", bin, args))

	cmd := exec.Command(bin, args...)
	hideCmdWindow(cmd)

	// 隔离 aitun 的工作目录，避免复用 ~/.aitun/auth_token（可能与其它程序冲突）
	aitunHome := filepath.Join(configDir(), ".aitun")
	os.MkdirAll(aitunHome, 0700)
	cmd.Env = append(os.Environ(), "HOME="+aitunHome)
	debugLog(fmt.Sprintf("[tunnel] aitun HOME: %s", aitunHome))

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		debugLog(fmt.Sprintf("[tunnel] stdout pipe error: %v", err))
		return fmt.Errorf("pipe failed: %w", err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		debugLog(fmt.Sprintf("[tunnel] stderr pipe error: %v", err))
		return fmt.Errorf("stderr pipe failed: %w", err)
	}
	if err := cmd.Start(); err != nil {
		debugLog(fmt.Sprintf("[tunnel] start error: %v", err))
		return fmt.Errorf("start aitun: %w", err)
	}
	debugLog(fmt.Sprintf("[tunnel] aitun started, PID: %d", cmd.Process.Pid))

	t.cmd = cmd
	t.stopCh = make(chan struct{})

	go t.parseOutput(stdout)
	go t.parseOutput(stderr)
	go t.monitor()

	t.running = true
	return nil
}

// monitor 监控 aitun 进程
func (t *Tunnel) monitor() {
	if t.cmd == nil || t.cmd.Process == nil {
		return
	}
	t.cmd.Wait()

	t.mu.Lock()
	wasRunning := t.running
	if t.running {
		t.running = false
		t.publicURL = ""
	}
	t.mu.Unlock()

	if wasRunning && onTunnelDied != nil {
		onTunnelDied()
	}
}

// Stop 停止隧道
func (t *Tunnel) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()

	if !t.running {
		return
	}
	t.running = false
	t.publicURL = ""
	close(t.stopCh)
	if t.cmd != nil && t.cmd.Process != nil {
		t.cmd.Process.Kill()
	}
}

// IsRunning 返回隧道是否在运行
func (t *Tunnel) IsRunning() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.running
}

// GetPublicURL 返回公共 URL
func (t *Tunnel) GetPublicURL() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.publicURL
}

var onTunnelDied func()

var urlRegex = regexp.MustCompile(`https?://[a-zA-Z0-9._/-]+`)

func (t *Tunnel) parseOutput(r io.Reader) {
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		select {
		case <-t.stopCh:
			return
		default:
		}
		line := scanner.Text()
		debugLog(fmt.Sprintf("[tunnel] aitun output: %s", line))
		if strings.Contains(line, "Proxy URL") || strings.Contains(line, "Subdomain mode") || strings.Contains(line, "aitun.cc") {
			if match := urlRegex.FindString(line); match != "" {
				t.mu.Lock()
				t.publicURL = match
				t.mu.Unlock()
				debugLog(fmt.Sprintf("[tunnel] captured URL: %s", match))
			}
		}
	}
}

// findAitunBinary 查找 aitun 二进制文件
func findAitunBinary() string {
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, name := range aitunBinaryNames() {
			p := filepath.Join(dir, name)
			if fileExists(p) {
				return p
			}
		}
	}
	for _, name := range aitunBinaryNames() {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, name := range aitunBinaryNames() {
			p := filepath.Join(dir, "bin", name)
			if fileExists(p) {
				return p
			}
		}
	}
	return ""
}

func aitunBinaryNames() []string {
	if runtime.GOOS == "windows" {
		return []string{"aitun-client.exe", "aitun.exe", "aitun"}
	}
	return []string{"aitun-client", "aitun"}
}
