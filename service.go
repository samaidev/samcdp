package main

// service.go — 核心服务管理：单隧道 + CDP 路由代理 + 多 Chrome 实例
// 架构:
//   远程 → https://mos.t.aitun.cc/agent-1/json/version
//                  ↓ aitun 隧道 (1条, 映射到代理端口 9333)
//            CDP 路由代理 (proxy.go, 端口 9333)
//              ├── /agent-1/* → 127.0.0.1:9334 (Chrome实例1)
//              ├── /agent-2/* → 127.0.0.1:9335 (Chrome实例2)
//              └── /         → 返回所有profile列表

import (
	"fmt"
	"net"
	"os/exec"
	"sync"
	"time"
)

// ProfileInstance 单个 profile 的运行时实例
type ProfileInstance struct {
	mu           sync.Mutex
	name         string
	port         int
	chromeCmd    *exec.Cmd
	chromeExe    string
	running      bool
	stopCh       chan struct{}
	timeoutTimer *time.Timer // 超时自动关闭 Chrome 的定时器
}

// Service 管理所有 profile 的服务
type Service struct {
	mu        sync.Mutex
	running   bool
	instances map[string]*ProfileInstance
	chromeExe string
	proxyPort int // CDP 路由代理监听端口
}

var svc = &Service{
	instances: make(map[string]*ProfileInstance),
	proxyPort: 9333,
}

// Start 启动服务：CDP 代理 + 多 Chrome 实例 + 单隧道
func (s *Service) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return fmt.Errorf("service already running")
	}

	// 1. 查找 Chrome
	chromePath, err := findChrome()
	if err != nil {
		return fmt.Errorf("chrome not found: %w (download: %s)", err, chromeDownloadURL())
	}
	s.chromeExe = chromePath

	// 1.5 启动前清理：杀掉所有旧 Chrome 进程，防止窗口堆积
	cleanupAllChrome()

	// 2. 启动 CDP 路由代理（端口 9333，绑定所有网卡：局域网地址作为隧道不可用时的备用入口）
	//    /api/* 有 token 鉴权；CDP 透传路径与隧道侧暴露面一致（产品设计如此）
	addr, err := cdpProxy.Start(fmt.Sprintf("0.0.0.0:%d", s.proxyPort))
	if err != nil {
		return fmt.Errorf("start cdp proxy on port %d: %w", s.proxyPort, err)
	}
	debugLog(fmt.Sprintf("[service] CDP proxy listening on %s", addr))

	// 3. 设置隧道崩溃回调
	onTunnelDied = func() {
		s.onTunnelDied()
	}

	// 4. 为每个 enabled profile 启动 Chrome（端口从 9334 起）
	profiles := cfg.GetEnabledProfiles()
	if len(profiles) == 0 {
		return fmt.Errorf("no enabled profiles")
	}

	for _, p := range profiles {
		// Chrome 端口从 9334 起（9333 是代理端口）
		chromePort := 9334
		if p.Port > 0 && p.Port != s.proxyPort {
			chromePort = p.Port
		} else {
			p.Port = chromePort
		}
		inst, err := s.startProfile(p, chromePath, 0) // 启动时不设超时（0=永久）
		if err != nil {
			fmt.Printf("[service] profile %s start failed: %v\n", p.Name, err)
			continue
		}
		s.instances[p.Name] = inst
	}

	if len(s.instances) == 0 {
		cdpProxy.Stop()
		return fmt.Errorf("all profiles failed to start")
	}

	// 5. 启动单条 aitun 隧道，映射到代理端口
	aitunKey := cfg.GetAitunKey()
	if err := tunnel.Start(s.proxyPort, aitunKey); err != nil {
		fmt.Printf("[service] tunnel start warning: %v\n", err)
		// 隧道失败不阻止服务，Chrome 仍在运行
	}

	s.running = true

	// 异步等待隧道 URL
	go s.waitForTunnelURL()

	return nil
}

// startProfile 启动单个 profile 的 Chrome，并注册到代理路由
// duration: 运行时长（分钟），到期后自动关闭 Chrome（防止窗口堆积）
func (s *Service) startProfile(p Profile, chromePath string, duration int) (*ProfileInstance, error) {
	// 启动 Chrome
	cmd, err := launchChromeWithCDP(chromePath, p.Port, p.Name)
	if err != nil {
		return nil, fmt.Errorf("launch chrome for %s: %w", p.Name, err)
	}

	// 等待 Chrome 端口就绪
	if !waitForPort(p.Port, 10*time.Second) {
		if cmd != nil && cmd.Process != nil {
			cmd.Process.Kill()
			cmd.Wait()
		}
		return nil, fmt.Errorf("chrome port %d not ready for %s", p.Port, p.Name)
	}

	// 注册到 CDP 路由代理
	cdpProxy.SetRoute(p.Name, fmt.Sprintf("http://127.0.0.1:%d", p.Port))

	inst := &ProfileInstance{
		name:         p.Name,
		port:         p.Port,
		chromeCmd:    cmd,
		chromeExe:    chromePath,
		running:      true,
		stopCh:       make(chan struct{}),
		timeoutTimer: nil,
	}

	// 设置超时定时器，到期自动关闭 Chrome
	if duration > 0 {
		inst.timeoutTimer = time.AfterFunc(time.Duration(duration)*time.Minute, func() {
			debugLog(fmt.Sprintf("[service] profile %s timeout (%d min), auto-stopping", p.Name, duration))
			s.stopProfileByName(p.Name)
		})
		debugLog(fmt.Sprintf("[service] profile %s will auto-stop after %d minutes", p.Name, duration))
	}

	// 监控 Chrome（用户关闭后自动重启，隧道不动）
	go s.monitorChrome(inst)

	return inst, nil
}

// startProfileByName 按名称启动一个 profile 的 Chrome（供 API 调用）
// duration: 运行时长（分钟），到期后自动关闭 Chrome（防止窗口堆积）
func (s *Service) startProfileByName(name string, duration int) error {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return fmt.Errorf("service not running")
	}
	if _, exists := s.instances[name]; exists {
		s.mu.Unlock()
		return fmt.Errorf("profile '%s' already running", name)
	}
	// 从配置中查找
	var prof Profile
	found := false
	for _, p := range cfg.GetProfiles() {
		if p.Name == name {
			prof = p
			found = true
			break
		}
	}
	if !found {
		s.mu.Unlock()
		return fmt.Errorf("profile '%s' not found in config", name)
	}
	chromePath := s.chromeExe
	s.mu.Unlock()

	inst, err := s.startProfile(prof, chromePath, duration)
	if err != nil {
		return err
	}

	s.mu.Lock()
	s.instances[name] = inst
	s.mu.Unlock()
	return nil
}

// stopProfileByName 按名称停止一个 profile 的 Chrome（供 API 调用）
func (s *Service) stopProfileByName(name string) error {
	s.mu.Lock()
	inst, ok := s.instances[name]
	if !ok {
		s.mu.Unlock()
		return fmt.Errorf("profile '%s' not running", name)
	}
	delete(s.instances, name)
	s.mu.Unlock()

	inst.mu.Lock()
	inst.running = false
	close(inst.stopCh)
	if inst.timeoutTimer != nil {
		inst.timeoutTimer.Stop()
		inst.timeoutTimer = nil
	}
	if inst.chromeCmd != nil && inst.chromeCmd.Process != nil {
		inst.chromeCmd.Process.Kill()
	}
	inst.mu.Unlock()

	cdpProxy.RemoveRoute(name)
	return nil
}

// monitorChrome 监控 Chrome 进程，用户关闭后自动重启（最多重试 2 次，不无限循环）
func (s *Service) monitorChrome(inst *ProfileInstance) {
	retries := 0
	maxRetries := 2
	for {
		select {
		case <-inst.stopCh:
			return
		default:
		}

		if inst.chromeCmd == nil || inst.chromeCmd.Process == nil {
			return
		}
		inst.chromeCmd.Wait()

		inst.mu.Lock()
		if !inst.running {
			inst.mu.Unlock()
			return
		}
		inst.mu.Unlock()

		// Chrome 退出了，检查是否达到最大重试次数
		if retries >= maxRetries {
			debugLog(fmt.Sprintf("[service] profile %s: chrome exited, max retries (%d) reached, not restarting", inst.name, maxRetries))
			return
		}
		retries++
		debugLog(fmt.Sprintf("[service] profile %s: chrome exited, restarting (attempt %d/%d)", inst.name, retries, maxRetries))

		// 等 2 秒后自动重启
		time.Sleep(2 * time.Second)

		inst.mu.Lock()
		if !inst.running {
			inst.mu.Unlock()
			return
		}
		// 重启 Chrome（launchChromeWithCDP 内部会先清理旧进程）
		cmd, err := launchChromeWithCDP(inst.chromeExe, inst.port, inst.name)
		if err != nil {
			inst.mu.Unlock()
			debugLog(fmt.Sprintf("[service] profile %s: restart failed: %v", inst.name, err))
			return
		}
		if cmd != nil {
			inst.chromeCmd = cmd
		}
		inst.mu.Unlock()
	}
}

// onTunnelDied 隧道崩溃回调：服务运行中则自动重启隧道（最多3次，间隔5s）
func (s *Service) onTunnelDied() {
	refreshMenuState()
	if !s.IsRunning() {
		return
	}
	go func() {
		for i := 1; i <= 3; i++ {
			time.Sleep(5 * time.Second)
			if !s.IsRunning() || tunnel.IsRunning() {
				return // 服务已停，或已被其他路径重启
			}
			debugLog(fmt.Sprintf("[service] tunnel died, auto-restart attempt %d/3", i))
			if err := tunnel.Start(s.proxyPort, cfg.GetAitunKey()); err == nil {
				debugLog("[service] tunnel auto-restart OK")
				refreshMenuState()
				return
			}
		}
		debugLog("[service] tunnel auto-restart failed after 3 attempts")
	}()
}

// waitForTunnelURL 等待隧道 URL 解析
func (s *Service) waitForTunnelURL() {
	for i := 0; i < 30; i++ {
		time.Sleep(500 * time.Millisecond)
		if tunnel.GetPublicURL() != "" {
			refreshMenuState()
			return
		}
	}
	refreshMenuState()
}

// waitForPort 等待端口可连接
func waitForPort(port int, timeout time.Duration) bool {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 500*time.Millisecond)
		if err == nil {
			conn.Close()
			return true
		}
		time.Sleep(200 * time.Millisecond)
	}
	return false
}

// Stop 停止所有服务
func (s *Service) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	instances := s.instances
	s.instances = make(map[string]*ProfileInstance)
	s.mu.Unlock()

	// 停止所有 Chrome
	for _, inst := range instances {
		inst.mu.Lock()
		inst.running = false
		close(inst.stopCh)
		if inst.timeoutTimer != nil {
			inst.timeoutTimer.Stop()
			inst.timeoutTimer = nil
		}
		if inst.chromeCmd != nil && inst.chromeCmd.Process != nil {
			inst.chromeCmd.Process.Kill()
		}
		inst.mu.Unlock()
	}

	// 停止隧道
	tunnel.Stop()

	// 停止代理
	cdpProxy.Stop()
}

// IsRunning 返回服务状态
func (s *Service) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

// GetRemoteAddress 返回隧道地址（兼容旧接口）
func (s *Service) GetRemoteAddress() string {
	return tunnel.GetPublicURL()
}

// GetCopyAddress 返回托盘"复制地址"用的可达地址（与设置页显示规则同源）：
// 隧道在线 → 隧道公网地址；否则 → 局域网地址（此时它是唯一可达入口）
func (s *Service) GetCopyAddress() string {
	if tunnel.IsRunning() {
		if u := tunnel.GetPublicURL(); u != "" {
			return u
		}
	}
	return s.GetLocalAddress()
}

// GetLocalAddress 返回局域网可达地址（探测默认路由出口 IP，失败回退 127.0.0.1）
func (s *Service) GetLocalAddress() string {
	if ip := outboundIP(); ip != "" {
		return fmt.Sprintf("http://%s:%d", ip, s.proxyPort)
	}
	return fmt.Sprintf("http://127.0.0.1:%d", s.proxyPort)
}

// outboundIP 通过 UDP connect 探测出口 IP（不实际发包，无副作用）
func outboundIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return ""
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok && !addr.IP.IsLoopback() {
		return addr.IP.String()
	}
	return ""
}

// GetToken 返回鉴权 token
func (s *Service) GetToken() string {
	return cfg.GetAuthToken()
}

// GetPublicURL 兼容旧接口
func (s *Service) GetPublicURL() string {
	return s.GetRemoteAddress()
}

// GetCDPPort 返回代理端口
func (s *Service) GetCDPPort() int {
	return s.proxyPort
}

// GetCDPAddress 返回本地代理地址
func (s *Service) GetCDPAddress() string {
	return fmt.Sprintf("http://127.0.0.1:%d", s.proxyPort)
}

// GetAllProfileStatus 返回所有 profile 的状态信息
func (s *Service) GetAllProfileStatus() []ProfileStatus {
	profiles := cfg.GetProfiles()
	tunnelURL := tunnel.GetPublicURL()
	token := cfg.GetAuthToken()

	result := make([]ProfileStatus, 0, len(profiles))
	for _, p := range profiles {
		status := ProfileStatus{
			Name:    p.Name,
			Port:    p.Port,
			Enabled: p.Enabled,
			Running: false,
		}
		if s.running {
			s.mu.Lock()
			inst, ok := s.instances[p.Name]
			s.mu.Unlock()
			status.Running = ok && inst.isRunning()
		}
		// 远程地址带 profile 前缀
		if tunnelURL != "" && status.Running {
			status.RemoteURL = tunnelURL + "/" + p.Name + "?token=" + token
		}
		result = append(result, status)
	}
	return result
}

// ProfileStatus 单个 profile 的运行时状态
type ProfileStatus struct {
	Name      string `json:"name"`
	Port      int    `json:"port"`
	Enabled   bool   `json:"enabled"`
	Running   bool   `json:"running"`
	RemoteURL string `json:"remote_url"`
}

// isRunning 返回实例是否在运行
func (inst *ProfileInstance) isRunning() bool {
	inst.mu.Lock()
	defer inst.mu.Unlock()
	return inst.running
}
