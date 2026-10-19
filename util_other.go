//go:build !windows

package main

// util_other.go — 非 Windows 平台工具函数

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// hideCmdWindow 非 Windows 平台空实现
func hideCmdWindow(cmd *exec.Cmd) {}

// debugLog 写调试日志到文件
func debugLog(msg string) {
	logDir := filepath.Join(os.Getenv("HOME"), ".config", "cdpclient")
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

func winRegQueryString(keyPath, valueName string) string {
	return ""
}

func winToast(msg string) {
	fmt.Println("[Toast]", msg)
	// macOS: 用 osascript 显示通知
	if runtime.GOOS == "darwin" {
		escaped := strings.ReplaceAll(msg, `"`, `\"`)
		execCmd("osascript", "-e", `display notification "`+escaped+`" with title "samcdp"`)
	}
}

func execPowerShell(script string) {}

func execCmd(name string, args ...string) {
	cmd := newCmd(name, args...)
	_ = cmd.Start()
}
