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
	"time"
)

// Tunnel 管理单个 aitun 隧道进程
type Tunnel struct {
	mu          sync.Mutex
	cmd         *exec.Cmd
	publicURL   string
	lastError   string
	running     bool
	stopped     bool // 主动停止标志：避免 Stop 后触发崩溃重启
	gen         int  // 进程代数：防止旧 monitor 竞态误清新进程状态
	stopCh      chan struct{}
	reusedToken bool   // v1.4.2: 本次启动复用了持久化 auth_token（<23h）
	authFile    string // v1.4.2: auth_token 文件路径（陈旧时清理用）
}

var tunnel = &Tunnel{}

// Start 启动 aitun 隧道，映射 localPort
func (t *Tunnel) Start(localPort int, aitunKey string) error {
	return t.start(localPort, aitunKey, true)
}

// start allowReuse: v1.4.6 — 临时代理模式下是否允许复用持久化 auth_token
// （仅首次允许；复用失败的 watchdog 重启传 false，强制换新身份，避免无限循环）
func (t *Tunnel) start(localPort int, aitunKey string, allowReuse bool) error {
	// v1.4.7: 客户端解析统一走 resolveAitunBinary（内嵌提取 > 磁盘查找 >
	// aitun.cc 下载）。在拿 t.mu 之前：提取/下载可能耗时，不能阻塞
	// IsRunning/GetPublicURL 等状态查询。失败把原因写进 lastError，
	// 设置页错误行立即可见（旧版只打 stdout，windowsgui 下无人能见）
	bin, err := resolveAitunBinary()
	if err != nil {
		t.mu.Lock()
		t.lastError = tf("tunnel.noClient", err)
		t.mu.Unlock()
		return fmt.Errorf("aitun binary unavailable (embed extract + disk lookup + download all failed): %w", err)
	}

	t.mu.Lock()
	defer t.mu.Unlock()

	if t.running {
		return fmt.Errorf("tunnel already running")
	}

	debugLog(fmt.Sprintf("[tunnel] aitun binary: %s", bin))

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

	// 隔离 aitun 的工作目录，避免复用 ~/.aitun/auth_token（可能与其它程序冲突）。
	// v1.4.8: 光设 HOME 在 Windows 上无效 —— 客户端(v4.12.6)不认 HOME/USERPROFILE
	// 重定向（走 Known-Folder 真实路径 C:\Users\<u>\.aitun\），在本机装有其他
	// aitun 的机器上会复用别人的身份 → 临时代理拿不到全新地址 / 进 Standby
	// 挂在别人隧道上。实测客户端尊重 AITUN_HOME（client_id/token 均落
	// $AITUN_HOME/.aitun/），与 HOME 同值；Linux/macOS HOME 本就有效，此为冗余兜底
	aitunHome := filepath.Join(configDir(), ".aitun")
	os.MkdirAll(aitunHome, 0700)
	// 无 key → 临时代理模式。aitun 服务端保证同一 auth_token 重新绑定到同一地址
	// （实测 v4.12.6："Reusing persisted auth_token for stable tunnel URL"，地址 24h 有效），
	// 因此 token 只要足够新（<23h 安全余量）就保留 → 重启后地址不变；
	// 仅在缺失/过期时才拿全新身份。v1.4.2 修复：旧逻辑无条件删 token，
	// 导致临时代理地址每次启动都变，尽管服务端 24h 有效。
	// 若复用失败（token 陈旧，如服务端丢记录），monitor 清理 token，
	// 自动重启路径自动拿全新地址（自愈）。
	t.reusedToken = false
	t.authFile = ""
	if aitunKey == "" {
		authFile := filepath.Join(aitunHome, ".aitun", "auth_token")
		if st, err := os.Stat(authFile); err == nil && allowReuse {
			age := time.Since(st.ModTime())
			if age < 23*time.Hour {
				t.reusedToken = true
				t.authFile = authFile
				debugLog(fmt.Sprintf("[tunnel] keeping auth_token (age %.1fh < 23h) — stable temp-proxy URL", age.Hours()))
			} else {
				os.Remove(authFile)
				debugLog("[tunnel] auth_token expired (>23h) — fresh temp-proxy identity")
			}
		} else if err == nil {
			os.Remove(authFile)
			debugLog("[tunnel] reuse retry: cleared auth_token for fresh temp-proxy identity")
		} else {
			debugLog("[tunnel] no key, no persisted token — fresh temp-proxy identity")
		}
	}
	cmd.Env = append(os.Environ(), "HOME="+aitunHome, "AITUN_HOME="+aitunHome)
	debugLog(fmt.Sprintf("[tunnel] aitun HOME/AITUN_HOME: %s", aitunHome))

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
	if t.reusedToken {
		go t.tempReuseWatchdog(myGen, localPort, aitunKey)
	}

	t.running = true
	return nil
}

// tempReuseWatchdog v1.4.6: 临时代理复用陈旧 auth_token 的补充自愈。
// v1.4.2 的 monitor 自愈只在进程退出时触发；若客户端复用失败后挂住不退
// （不打印地址也不崩），15s 内无 URL → 清 token 重启一次，强制全新身份
// （allowReuse=false 不再复用、不再递归）
func (t *Tunnel) tempReuseWatchdog(myGen int, localPort int, aitunKey string) {
	for i := 0; i < 30; i++ {
		time.Sleep(500 * time.Millisecond)
		t.mu.Lock()
		same := t.gen == myGen && t.running
		url := t.publicURL
		t.mu.Unlock()
		if url != "" {
			return // 上线成功
		}
		if !same {
			return // 已被停止/换代
		}
	}
	debugLog("[tunnel] no URL after 15s with reused auth_token — clearing and restarting fresh")
	t.mu.Lock()
	t.reusedToken = false
	authFile := t.authFile
	t.mu.Unlock()
	if authFile != "" {
		os.Remove(authFile)
	}
	t.Stop()
	time.Sleep(300 * time.Millisecond)
	t.start(localPort, aitunKey, false)
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
	sameGen := t.gen == myGen
	usedToken := sameGen && t.reusedToken
	gotURL := t.publicURL != ""
	authFile := t.authFile
	if sameGen && t.running {
		t.running = false
		t.publicURL = ""
		if t.lastError == "" {
			t.lastError = tf("tunnel.exit", err) // v1.4.0: language-aware
		}
	}
	crashed := sameGen && !t.stopped // 主动 Stop 不算崩溃
	t.mu.Unlock()

	// v1.4.2 自愈：复用的 auth_token 全程没拿到地址 → token 陈旧（服务端丢记录等），
	// 清掉它，让下一次自动重启拿全新临时地址，而不是带着坏 token 反复失败
	if crashed && usedToken && !gotURL {
		t.mu.Lock()
		t.reusedToken = false
		t.mu.Unlock()
		if authFile != "" {
			if rmErr := os.Remove(authFile); rmErr == nil {
				debugLog("[tunnel] reused token produced no URL — removed stale auth_token for fresh identity on next restart")
			}
		}
	}

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
