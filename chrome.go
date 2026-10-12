package main

// chrome.go — Chrome 浏览器检测与 CDP 启动（多 profile 支持）

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// findChrome 查找 Chrome 安装路径
func findChrome() (string, error) {
	switch runtime.GOOS {
	case "windows":
		return findChromeWindows()
	case "darwin":
		return findChromeMac()
	default:
		return findChromeLinux()
	}
}

func findChromeWindows() (string, error) {
	paths := []string{
		`C:\Program Files\Google\Chrome\Application\chrome.exe`,
		`C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`,
		filepath.Join(getLocalAppData(), "Google", "Chrome", "Application", "chrome.exe"),
	}
	for _, p := range paths {
		if fileExists(p) {
			return p, nil
		}
	}
	if p := regQueryChrome(); p != "" {
		return p, nil
	}
	return "", fmt.Errorf("chrome not found")
}

func findChromeMac() (string, error) {
	p := "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
	if fileExists(p) {
		return p, nil
	}
	return "", fmt.Errorf("chrome not found")
}

func findChromeLinux() (string, error) {
	for _, name := range []string{"google-chrome", "google-chrome-stable", "chromium-browser", "chromium"} {
		if p, err := exec.LookPath(name); err == nil {
			return p, nil
		}
	}
	return "", fmt.Errorf("chrome not found")
}

// launchChromeWithCDP 启动 Chrome 并开启远程调试端口
// profileName 用于区分不同 profile 的 user-data-dir
func launchChromeWithCDP(exePath string, port int, profileName string) (*exec.Cmd, error) {
	// Linux: 预置企业策略，关闭 "--no-sandbox 不支持" 黄色警告条
	if runtime.GOOS == "linux" {
		setupChromePolicyNoSandboxWarning()
	}

	// 启动前清理：杀掉占用该端口的旧 Chrome 进程，防止窗口堆积
	killChromeOnPort(port)

	userDataDir := filepath.Join(getAppDataDir(), "chrome-profile-"+profileName)
	args := []string{
		fmt.Sprintf("--remote-debugging-port=%d", port),
		fmt.Sprintf("--user-data-dir=%s", userDataDir),
		"--no-first-run",
		"--no-default-browser-check",
		"--keep-alive-for-test",
	}
	// Linux 容器/无桌面环境需要 headless + no-sandbox
	if runtime.GOOS == "linux" {
		if os.Getenv("DISPLAY") == "" {
			args = append(args, "--headless=new")
		}
		args = append(args, "--no-sandbox", "--disable-dev-shm-usage")
	}
	cmd := exec.Command(exePath, args...)
	hideCmdWindow(cmd)
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("failed to start chrome: %w", err)
	}
	return cmd, nil
}

// setupChromePolicyNoSandboxWarning 在 Linux 上写入 Chrome 企业策略文件，
// 关闭 "您正在使用不受支持的命令行标记: --no-sandbox" 黄色警告条。
// 策略文件路径: /etc/opt/chrome/policies/managed/cdpclient.json
// 策略内容: {"CommandLineFlagSecurityWarningsEnabled": false}
func setupChromePolicyNoSandboxWarning() {
	policyDir := "/etc/opt/chrome/policies/managed"
	policyFile := policyDir + "/cdpclient.json"

	// 如果策略文件已存在且内容正确，跳过
	if content, err := os.ReadFile(policyFile); err == nil &&
		strings.Contains(string(content), "CommandLineFlagSecurityWarningsEnabled") {
		return
	}

	// 创建策略目录
	os.MkdirAll(policyDir, 0755)

	// 写入策略 JSON
	policy := `{"CommandLineFlagSecurityWarningsEnabled": false}`
	os.WriteFile(policyFile, []byte(policy), 0644)
	debugLog("[chrome] wrote policy file: " + policyFile)
}

// killChromeOnPort 杀掉占用指定端口的 Chrome 进程，防止窗口堆积
func killChromeOnPort(port int) {
	// 通过 fuser 查找占用端口的进程
	cmd := exec.Command("fuser", fmt.Sprintf("%d/tcp", port))
	output, err := cmd.Output()
	if err != nil || len(output) == 0 {
		return // 端口未被占用
	}
	// output 格式: "9334:  12345 12346"
	pids := strings.Fields(strings.TrimPrefix(string(output), fmt.Sprintf("%d:", port)))
	for _, pidStr := range pids {
		if pid, err := parsePid(pidStr); err == nil {
			exec.Command("kill", "-9", fmt.Sprintf("%d", pid)).Run()
			debugLog(fmt.Sprintf("[chrome] killed old process %d on port %d", pid, port))
		}
	}
	time.Sleep(1 * time.Second) // 等待端口释放
}

// cleanupAllChrome 杀掉所有残留的 Chrome 进程（启动前调用）
func cleanupAllChrome() {
	if runtime.GOOS == "windows" {
		// Windows: 用 taskkill
		cmd := exec.Command("taskkill", "/F", "/IM", "chrome.exe")
		cmd.Run()
	} else {
		// Linux/Mac: 用 pkill
		cmd := exec.Command("pkill", "-9", "-f", "chrome.*remote-debugging-port")
		if err := cmd.Run(); err != nil {
			debugLog("[chrome] cleanup: no leftover chrome processes or pkill unavailable")
			return
		}
		time.Sleep(2 * time.Second) // 等待端口释放
	}
	debugLog("[chrome] cleanup: killed leftover chrome processes")
}

func parsePid(s string) (int, error) {
	var pid int
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, fmt.Errorf("not a pid: %s", s)
		}
		pid = pid*10 + int(c-'0')
	}
	if pid == 0 {
		return 0, fmt.Errorf("empty pid")
	}
	return pid, nil
}

func chromeDownloadURL() string {
	return "https://www.google.com/chrome/"
}

func validateChromePath(path string) bool {
	if path == "" {
		return false
	}
	lower := strings.ToLower(path)
	return strings.Contains(lower, "chrome") && fileExists(path)
}
