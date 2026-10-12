package main

// file_helpers.go — 文件操作辅助函数 (避免循环依赖)

import (
	"os"
	"path/filepath"
)

func osExec() (string, error) {
	return os.Executable()
}

func filepathDirImpl(p string) string {
	return filepath.Dir(p)
}

func readFileImpl(p string) []byte {
	data, err := os.ReadFile(p)
	if err != nil {
		return nil
	}
	return data
}

func filepathJoinImpl(elem ...string) string {
	return filepath.Join(elem...)
}
