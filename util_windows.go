//go:build windows

package main

// util_windows.go — Windows 专用工具函数

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"

	"golang.org/x/sys/windows/registry"
)

// debugLog 写调试日志到文件
func debugLog(msg string) {
	logDir := filepath.Join(os.Getenv("APPDATA"), "cdpclient")
	os.MkdirAll(logDir, 0755)
	logFile := filepath.Join(logDir, "debug.log")
	f, err := os.OpenFile(logFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	defer f.Close()
	ts := time.Now().Format("2006-01-02 15:04:05.000")
	fmt.Fprintf(f, "%s %s\n", ts, msg)
}

// hideCmdWindow 设置 CREATE_NO_WINDOW 标志，防止控制台程序弹出 CMD 窗口
func hideCmdWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
}

// winRegQueryString 从注册表读取字符串值
func winRegQueryString(keyPath, valueName string) string {
	// 尝试 HKLM 64位
	if v := regReadString(registry.LOCAL_MACHINE, keyPath, valueName); v != "" {
		return v
	}
	// 尝试 HKLM 32位 (WOW6432Node)
	wowPath := `SOFTWARE\WOW6432Node` + keyPath[len("SOFTWARE"):]
	if v := regReadString(registry.LOCAL_MACHINE, wowPath, valueName); v != "" {
		return v
	}
	// 尝试 HKCU
	if v := regReadString(registry.CURRENT_USER, keyPath, valueName); v != "" {
		return v
	}
	return ""
}

func regReadString(root registry.Key, path, name string) string {
	k, err := registry.OpenKey(root, path, registry.QUERY_VALUE)
	if err != nil {
		return ""
	}
	defer k.Close()
	val, _, err := k.GetStringValue(name)
	if err != nil {
		// 尝试读取默认值
		if name == "" {
			val, _, err = k.GetStringValue("")
			if err != nil {
				return ""
			}
			return val
		}
		return ""
	}
	return val
}

// winToast 显示 Windows 通知（通过 PowerShell 或 MessageBox）
func winToast(msg string) {
	// 使用 MessageBox 作为简单通知
	fmt.Println("[Toast]", msg)
	// 异步弹窗不阻塞
	go func() {
		execPowerShell(
			`Add-Type -AssemblyName System.Windows.Forms; ` +
				`$notify = New-Object System.Windows.Forms.NotifyIcon; ` +
				`$notify.Icon = [System.Drawing.SystemIcons]::Information; ` +
				`$notify.Visible = $true; ` +
				`$notify.ShowBalloonTip(3000, 'CDP Client', '` + msg + `', [System.Windows.Forms.ToolTipIcon]::Info); ` +
				`Start-Sleep -Seconds 4; ` +
				`$notify.Dispose()`)
	}()
}

// execPowerShell 执行 PowerShell 命令
func execPowerShell(script string) {
	execCmd("powershell", "-NoProfile", "-Command", script)
}

func execCmd(name string, args ...string) {
	cmd := newCmd(name, args...)
	_ = cmd.Start()
}
