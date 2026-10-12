//go:build windows

package main

// autostart_windows.go — Windows 注册表操作

import (
	"fmt"
	"os"
	"strings"

	"golang.org/x/sys/windows/registry"
)

func openRegKey(path string, write bool) (registry.Key, error) {
	if write {
		return registry.OpenKey(registry.CURRENT_USER, path, registry.SET_VALUE|registry.QUERY_VALUE)
	}
	return registry.OpenKey(registry.CURRENT_USER, path, registry.QUERY_VALUE)
}

func winIsAutoStartEnabled() (bool, error) {
	key, err := openRegKey(winRegistryKey, false)
	if err != nil {
		return false, err
	}
	defer key.Close()

	val, _, err := key.GetStringValue(winAppName)
	if err != nil {
		return false, nil // not set
	}
	exePath, _ := os.Executable()
	stored := strings.Trim(strings.TrimSpace(val), `"`)
	return strings.EqualFold(stored, exePath), nil
}

func winEnableAutoStart() error {
	key, err := openRegKey(winRegistryKey, true)
	if err != nil {
		return err
	}
	defer key.Close()

	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	val := exePath
	if strings.Contains(exePath, " ") {
		val = fmt.Sprintf(`"%s"`, exePath)
	}
	return key.SetStringValue(winAppName, val)
}

func winDisableAutoStart() error {
	key, err := openRegKey(winRegistryKey, true)
	if err != nil {
		return err
	}
	defer key.Close()
	_ = key.DeleteValue(winAppName)
	return nil
}
