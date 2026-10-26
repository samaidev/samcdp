package main

// tunnel_fetch_test.go — v1.4.7 桌面端集成测试：
// ① 内嵌 aitun 客户端提取 ② 隧道临时代理上线 ③ 重启后 URL 稳定（23h token 复用）
// ④ 复用 token 被服务端拒绝（记录丢失）时模拟 service.onTunnelDied 自愈重启
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

// startWithHeal 启动隧道，失败时模拟真实 app 的自愈链路（service.go
// onTunnelDied → 重启 ×3）：典型场景是复用的 auth_token 被服务端拒绝
// （tunnel not found）——客户端 Fatal 退出后 monitor 清掉 token 文件，
// 下一次 start 拿全新 client_id 身份即可上线。
func startWithHeal(t *testing.T) {
	t.Helper()
	for i := 0; i < 3; i++ {
		if err := tunnel.Start(19555, ""); err != nil {
			t.Fatalf("tunnel start: %v", err)
		}
		if u := waitURL(t, 30*time.Second); u != "" {
			return
		}
		t.Logf("start attempt %d failed (lastError=%s) — self-heal restart", i+1, tunnel.GetError())
		tunnel.Stop()
		time.Sleep(1 * time.Second)
	}
	t.Fatalf("tunnel not online after 3 self-heal attempts; lastError=%s", tunnel.GetError())
}

func TestAitunFetchAndTunnel(t *testing.T) {
	initConfig()

	// aitun 客户端要求本地端口先有监听（否则报 local service not available 并退出）
	ln, err := net.Listen("tcp", "127.0.0.1:19555")
	if err != nil {
		t.Fatalf("dummy listen: %v", err)
	}
	defer ln.Close()

	// 记录客户端状态：v1.4.7 内嵌客户端应自动提取到部署目录
	binBefore := findAitunBinary()
	t.Logf("aitun binary before: %q (embedded=%d B)", binBefore, len(aitunEmbedded))

	startWithHeal(t)
	u1 := waitURL(t, 5*time.Second)
	if u1 == "" {
		t.Fatalf("no tunnel URL; lastError=%s", tunnel.GetError())
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
	t.Logf("aitun client installed: %s (%d B)", binAfter, fi.Size())
	if fi.Size() != int64(len(aitunEmbedded)) {
		t.Fatalf("extracted client size %d != embedded %d (stale on-disk client?)", fi.Size(), len(aitunEmbedded))
	}

	// token 文件必须已持久化（地址稳定的前提）
	tok := filepath.Join(configDir(), ".aitun", ".aitun", "auth_token")
	if _, err := os.Stat(tok); err != nil {
		t.Fatalf("auth_token missing: %v", err)
	}

	// 重启 → 23h 内复用 token → URL 必须不变
	tunnel.Stop()
	time.Sleep(1 * time.Second)
	startWithHeal(t)
	u2 := waitURL(t, 5*time.Second)
	if u2 == "" {
		t.Fatalf("no URL after restart; lastError=%s", tunnel.GetError())
	}
	if u1 != u2 {
		t.Fatalf("URL changed across restart: %s -> %s", u1, u2)
	}
	t.Logf("URL stable across restart: %s", u2)

	tunnel.Stop()
}
