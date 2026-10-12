package main

// clipboard.go — 跨平台剪切板操作

import (
	"fmt"
	"io"
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
	// 优先用 pbcopy
	err := execCommandStdin("pbcopy", text)
	if err != nil {
		debugLog(fmt.Sprintf("[clipboard] pbcopy failed: %v, trying osascript", err))
		// 备选方案：用 osascript
		escaped := strings.ReplaceAll(text, `\`, `\\`)
		escaped = strings.ReplaceAll(escaped, `"`, `\"`)
		return execCommand("osascript", "-e", `set the clipboard to "`+escaped+`"`)
	}
	debugLog("[clipboard] pbcopy OK")
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
