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
	lastError string
	running   bool
	stopped   bool // 主动停止标志：避免 Stop 后触发崩溃重启
	gen       int  // 进程代数：防止旧 monitor 竞态误清新进程状态
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

	// 新一代进程：递增 gen，旧 monitor 回收时凭 gen 判断是否属于自己
	t.gen++
	myGen := t.gen
	t.publicURL = ""
	t.lastError = ""
	t.stopped = false
	t.cmd = cmd
	t.stopCh = make(chan struct{})

	go t.parseOutput(stdout, myGen)
	go t.parseOutput(stderr, myGen)
	go t.monitor(myGen)

	t.running = true
	return nil
}

// monitor 监控 aitun 进程（myGen: 启动时的代数，仅当代数仍匹配时才清理状态）
func (t *Tunnel) monitor(myGen int) {
	t.mu.Lock()
	cmd := t.cmd
	t.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return
	}
	err := cmd.Wait()
	if err != nil {
		debugLog(fmt.Sprintf("[tunnel] aitun exited (gen %d): %v", myGen, err))
	}

	t.mu.Lock()
	if t.gen == myGen && t.running {
		t.running = false
		t.publicURL = ""
		if t.lastError == "" {
			t.lastError = fmt.Sprintf("aitun 进程退出 (exit: %v)", err)
		}
	}
	crashed := t.gen == myGen && !t.stopped // 主动 Stop 不算崩溃
	t.mu.Unlock()

	if crashed && onTunnelDied != nil {
		onTunnelDied()
	}
}

// Stop 停止隧道
func (t *Tunnel) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()

	t.stopped = true
	if !t.running {
		return
	}
	t.running = false
	t.publicURL = ""
	t.lastError = ""
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

// GetError 返回最近一次隧道错误（空串=无错误）
func (t *Tunnel) GetError() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.lastError
}

var onTunnelDied func()

var urlRegex = regexp.MustCompile(`https?://[a-zA-Z0-9._/-]+`)

func (t *Tunnel) parseOutput(r io.Reader, myGen int) {
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
				if t.gen == myGen {
					t.publicURL = match
					t.lastError = "" // 上线成功，清除错误
				}
				t.mu.Unlock()
				debugLog(fmt.Sprintf("[tunnel] captured URL: %s", match))
			}
		}
		// 错误行捕获（实时展示到设置页，与 Android v1.1.6 行为一致）
		low := strings.ToLower(line)
		if strings.Contains(low, "error") || strings.Contains(low, "failed") || strings.Contains(low, "refused") {
			t.mu.Lock()
			if t.gen == myGen {
				t.lastError = strings.TrimSpace(line)
			}
			t.mu.Unlock()
		}
	}
}

// findAitunBinary 查找 aitun 二进制文件
func findAitunBinary() string {
	// 1. 可执行文件同目录（部署标准位置）
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		for _, name := range aitunBinaryNames() {
			p := filepath.Join(dir, name)
			if fileExists(p) {
				return p
			}
		}
	}
	// 2. PATH
	for _, name := range aitunBinaryNames() {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}
	// 3. 常见绝对路径（launchd/SSH 启动时 PATH 常不含 /usr/local/bin）
	for _, name := range aitunBinaryNames() {
		for _, dir := range []string{"/usr/local/bin", "/opt/homebrew/bin", "/usr/bin"} {
			p := filepath.Join(dir, name)
			if fileExists(p) {
				return p
			}
		}
	}
	// 4. 可执行文件同目录 bin/ 子目录
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
