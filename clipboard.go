package main

// clipboard.go — 跨平台剪切板操作

import (
	"fmt"
	"io"
	"os/exec"
	"runtime"
	"strings"
)

// copyToClipboard 将文本复制到剪切板
func copyToClipboard(text string) error {
	switch runtime.GOOS {
	case "windows":
		return winCopyClipboard(text)
	case "darwin":
		return macCopyClipboard(text)
	default:
		return linuxCopyClipboard(text)
	}
}

func winCopyClipboard(text string) error {
	// 使用 PowerShell 将文本写入剪切板
	ps := fmt.Sprintf(`Set-Clipboard -Value '%s'`, escapeSingleQuote(text))
	return execCommand("powershell", "-NoProfile", "-Command", ps)
}

func macCopyClipboard(text string) error {
	// 绝对路径优先：GUI/launchd 环境可能 PATH 不全导致 exec "pbcopy" 失败
	err := execCommandStdin("/usr/bin/pbcopy", text)
	if err == nil {
		debugLog(fmt.Sprintf("[clipboard] /usr/bin/pbcopy OK (%d bytes)", len(text)))
		return nil
	}
	debugLog(fmt.Sprintf("[clipboard] /usr/bin/pbcopy failed: %v", err))
	// 回退 1：按 PATH 查找 pbcopy
	if p, lookErr := exec.LookPath("pbcopy"); lookErr == nil && p != "/usr/bin/pbcopy" {
		if err2 := execCommandStdin(p, text); err2 == nil {
			debugLog("[clipboard] pbcopy(PATH) OK")
			return nil
		} else {
			err = err2
		}
	}
	// 回退 2：osascript（同样用绝对路径）
	escaped := strings.ReplaceAll(text, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `"`, `\"`)
	if err2 := execCommand("/usr/bin/osascript", "-e", `set the clipboard to "`+escaped+`"`); err2 != nil {
		debugLog(fmt.Sprintf("[clipboard] all methods failed: pbcopy=%v osascript=%v", err, err2))
		return fmt.Errorf("pbcopy: %v; osascript: %v", err, err2)
	}
	debugLog("[clipboard] osascript OK")
	return nil
}

func linuxCopyClipboard(text string) error {
	return execCommandStdin("xclip", "-selection", "clipboard", text)
}

func escapeSingleQuote(s string) string {
	out := ""
	for _, c := range s {
		if c == '\'' {
			out += "''" // PowerShell 转义
		} else {
			out += string(c)
		}
	}
	return out
}

func execCommand(name string, args ...string) error {
	cmd := newCmd(name, args...)
	return cmd.Run()
}

func execCommandStdin(name string, args ...string) error {
	// 最后一个参数是 stdin 文本
	stdinText := ""
	if len(args) > 0 {
		stdinText = args[len(args)-1]
		args = args[:len(args)-1]
	}
	cmd := newCmd(name, args...)
	cmd.Stdin = io.NopCloser(strings.NewReader(stdinText))
	return cmd.Run()
}
