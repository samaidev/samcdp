package main

// cmd_util.go — 跨平台进程启动辅助

import "os/exec"

func newCmd(name string, args ...string) *exec.Cmd {
	cmd := exec.Command(name, args...)
	hideCmdWindow(cmd) // Windows: CREATE_NO_WINDOW，防止弹出 CMD 窗口
	return cmd
}
