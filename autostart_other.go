//go:build !windows

package main

// autostart_other.go — 非 Windows 平台的 Windows autostart 函数 stub
// (autostart.go 的 switch 在非 Windows 平台不会调用这些函数，但编译器需要符号存在)

func winIsAutoStartEnabled() (bool, error) { return false, nil }
func winEnableAutoStart() error            { return nil }
func winDisableAutoStart() error            { return nil }
