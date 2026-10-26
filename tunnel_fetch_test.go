package main

// tunnel_fetch_test.go — v1.4.6 桌面端修复的集成测试：
// ① aitun 客户端缺失时自动下载 ② 隧道临时代理上线 ③ 重启后 URL 稳定（23h token 复用）
// 运行: go test -run TestAitunFetchAndTunnel -v -timeout 300s

import (
        "net"
        "os"
        "path/filepath"
        "testing"
        "time"
)

func waitURL(t *testing.T, timeout time.Duration) string {
        t.Helper()
        deadline := time.Now().Add(timeout)
        for time.Now().Before(deadline) {
                if u := tunnel.GetPublicURL(); u != "" {
                        return u
                }
                time.Sleep(500 * time.Millisecond)
        }
        return ""
}

func TestAitunFetchAndTunnel(t *testing.T) {
        initConfig()

        // aitun 客户端要求本地端口先有监听（否则报 local service not available 并退出）
        ln, err := net.Listen("tcp", "127.0.0.1:19555")
        if err != nil {
                t.Fatalf("dummy listen: %v", err)
        }
        defer ln.Close()

        // 记录客户端状态：测试环境应无 aitun-client → 触发自动下载
        binBefore := findAitunBinary()
        t.Logf("aitun binary before: %q", binBefore)

        if err := tunnel.Start(19555, ""); err != nil {
                t.Fatalf("tunnel start: %v", err)
        }
        u1 := waitURL(t, 90*time.Second)
        if u1 == "" {
                t.Fatalf("no tunnel URL in 90s; lastError=%s", tunnel.GetError())
        }
        t.Logf("tunnel online: %s", u1)

        binAfter := findAitunBinary()
        if binAfter == "" {
                t.Fatalf("aitun client still missing after successful start")
        }
        fi, err := os.Stat(binAfter)
        if err != nil {
                t.Fatalf("stat client: %v", err)
        }
        t.Logf("aitun client auto-installed: %s (%d B)", binAfter, fi.Size())

        // token 文件必须已持久化（地址稳定的前提）
        tok := filepath.Join(configDir(), ".aitun", ".aitun", "auth_token")
        if _, err := os.Stat(tok); err != nil {
                t.Fatalf("auth_token missing: %v", err)
        }

        // 重启 → 23h 内复用 token → URL 必须不变
        tunnel.Stop()
        time.Sleep(1 * time.Second)
        if err := tunnel.Start(19555, ""); err != nil {
                t.Fatalf("tunnel restart: %v", err)
        }
        u2 := waitURL(t, 90*time.Second)
        if u2 == "" {
                t.Fatalf("no URL after restart; lastError=%s", tunnel.GetError())
        }
        if u1 != u2 {
                t.Fatalf("URL changed across restart: %s -> %s", u1, u2)
        }
        t.Logf("URL stable across restart: %s", u2)

        tunnel.Stop()
}
