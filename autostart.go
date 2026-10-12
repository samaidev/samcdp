package main

// autostart.go — 开机启动管理 (跨平台调度)

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

const (
	winRegistryKey = `Software\Microsoft\Windows\CurrentVersion\Run`
	winAppName     = "cdpclient"
)

// IsAutoStartEnabled 检查是否已设置开机启动
func IsAutoStartEnabled() (bool, error) {
	switch runtime.GOOS {
	case "windows":
		return winIsAutoStartEnabled()
	case "darwin":
		return macIsAutoStartEnabled()
	case "linux":
		return linuxIsAutoStartEnabled()
	default:
		return false, fmt.Errorf("unsupported OS: %s", runtime.GOOS)
	}
}

// EnableAutoStart 设置开机启动
func EnableAutoStart() error {
	switch runtime.GOOS {
	case "windows":
		return winEnableAutoStart()
	case "darwin":
		return macEnableAutoStart()
	case "linux":
		return linuxEnableAutoStart()
	default:
		return fmt.Errorf("unsupported OS: %s", runtime.GOOS)
	}
}

// DisableAutoStart 取消开机启动
func DisableAutoStart() error {
	switch runtime.GOOS {
	case "windows":
		return winDisableAutoStart()
	case "darwin":
		return macDisableAutoStart()
	case "linux":
		return linuxDisableAutoStart()
	default:
		return fmt.Errorf("unsupported OS: %s", runtime.GOOS)
	}
}

// --- macOS ---

func launchAgentPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "LaunchAgents", "com.cdpclient.plist")
}

func macIsAutoStartEnabled() (bool, error) {
	_, err := os.Stat(launchAgentPath())
	return err == nil, nil
}

func macEnableAutoStart() error {
	exe, _ := os.Executable()
	content := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.cdpclient</string>
    <key>ProgramArguments</key>
    <array>
        <string>%s</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>`, exe)
	dir := filepath.Dir(launchAgentPath())
	os.MkdirAll(dir, 0755)
	return os.WriteFile(launchAgentPath(), []byte(content), 0644)
}

func macDisableAutoStart() error {
	return os.Remove(launchAgentPath())
}

// --- Linux (systemd user service) ---

func systemdServicePath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "systemd", "user", "cdpclient.service")
}

func linuxIsAutoStartEnabled() (bool, error) {
	_, err := os.Stat(systemdServicePath())
	return err == nil, nil
}

func linuxEnableAutoStart() error {
	exe, _ := os.Executable()
	content := fmt.Sprintf(`[Unit]
Description=CDP Client
After=network.target

[Service]
ExecStart=%s
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
`, exe)
	dir := filepath.Dir(systemdServicePath())
	os.MkdirAll(dir, 0755)
	if err := os.WriteFile(systemdServicePath(), []byte(content), 0644); err != nil {
		return err
	}
	return nil
}

func linuxDisableAutoStart() error {
	return os.Remove(systemdServicePath())
}
