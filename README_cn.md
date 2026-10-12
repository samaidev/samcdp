# SamCDP 中文文档

通过 [aitun](https://aitun.cc) 隧道，将本地 Chrome CDP（Chrome DevTools Protocol）调试端口暴露为公网 HTTPS 地址，供远程智能体调用。

[English README](README.md)

---

## 功能特性

- **多 Profile Chrome 管理** — 同时运行多个独立 Chrome 实例，各自拥有独立的 user-data-dir 和调试端口
- **单隧道 + 路径路由** — 一条 aitun 隧道通过路径路由（`/profile-name/json/list`）转发到所有 Chrome 实例
- **CDP 管理 API** — RESTful 接口启动/停止 Chrome、列出/打开/关闭标签页、透传原生 CDP 协议
- **系统托盘集成** — 原生系统托盘图标，支持运行/停止/设置菜单（Windows & Linux）
- **自动清理** — 启动时自动杀掉残留 Chrome 进程，防止窗口堆积
- **超时自动关闭** — 启动 Chrome 时可指定运行时长（分钟），到期自动关闭
- **登录状态持久化** — Chrome user-data-dir 保留 Cookie/session，重启后无需重新登录
- **跨平台** — 支持 Windows 和 Linux（amd64）

## 架构

```
远程智能体
    │
    ▼ HTTPS
┌───────────────────────────────┐
│  aitun 隧道（公网 URL）       │  例：https://mos.t.aitun.cc
└───────────┬───────────────────┘
            │
            ▼
┌───────────────────────────────┐
│  SamCDP CDP 代理（端口 9333）│  路径路由 + 管理 API
└───┬───────────┬───────────┬───┘
    │           │           │
    ▼           ▼           ▼
 Chrome:9334 Chrome:9335 Chrome:9336
 (agent-1)   (agent-2)   (agent-3)
```

## 一键安装

### Linux（bash）

```bash
# 一行安装
curl -fsSL https://raw.githubusercontent.com/samaidev/samcdp/main/install.sh | bash
```

<details>
<summary>手动安装（Linux）</summary>

```bash
# 下载源码并编译
git clone https://github.com/samaidev/samcdp.git
cd samcdp
CGO_ENABLED=0 go build -o cdpclient .

# 下载 aitun-client
wget -O aitun-client https://aitun.cc/downloads/aitun-client-linux-amd64
chmod +x aitun-client

# 运行
export DISPLAY=:1
./cdpclient
```
</details>

### Windows（PowerShell）

```powershell
# 一行安装
irm https://raw.githubusercontent.com/samaidev/samcdp/main/install.ps1 | iex
```

<details>
<summary>手动安装（Windows）</summary>

```powershell
# 克隆并编译
git clone https://github.com/samaidev/samcdp.git
cd samcdp
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# 运行
.\cdpclient.exe
```
</details>

## 配置

首次启动时，SamCDP 会自动创建配置文件：
- **Windows:** `%APPDATA%\cdpclient\config.json`
- **Linux:** `~/.config/cdpclient/config.json`

```json
{
  "aitun_key": "ait_你的_aitun_密钥",
  "auth_token": "自动生成的令牌",
  "lang": "zh",
  "profiles": [
    {"name": "agent-1", "port": 9334, "enabled": true},
    {"name": "agent-2", "port": 9335, "enabled": true},
    {"name": "agent-3", "port": 9336, "enabled": true}
  ]
}
```

### 获取 aitun 密钥

1. 在 [aitun.cc](https://aitun.cc) 注册账号
2. 验证邮箱
3. 注册子域名并获取密钥（`ait_xxxx`）

## API 参考

所有接口需要鉴权：通过 `?token=YOUR_TOKEN` 查询参数或 `Authorization: Bearer YOUR_TOKEN` 请求头。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/?token=xxx` | API 文档页面 |
| `GET` | `/api/profiles?token=xxx` | 列出所有 profile |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=分钟` | 启动 Chrome（duration 必填，单位：分钟） |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | 停止 Chrome |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | 列出标签页 |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&token=xxx` | 打开新标签页 |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | 关闭标签页 |
| — | `/<profile>/json/*` | CDP 原生协议透传 |

### 示例：启动 Chrome 并自动化操作

```bash
# 启动 agent-1 的 Chrome（30 分钟超时）
curl -X POST "https://你的隧道地址/api/profiles/agent-1/start?token=你的令牌&duration=30"

# 列出标签页
curl "https://你的隧道地址/api/profiles/agent-1/tabs?token=你的令牌"

# 打开 DeepSeek
curl -X POST "https://你的隧道地址/api/profiles/agent-1/tabs/new?url=https://chat.deepseek.com&token=你的令牌"

# 通过 CDP WebSocket 连接
wss://你的隧道地址/agent-1/json/version
```

### Python 示例

```python
import requests

BASE = "https://你的隧道地址"
TOKEN = "你的令牌"

# 启动 Chrome（30 分钟）
requests.post(f"{BASE}/api/profiles/agent-1/start", params={"token": TOKEN, "duration": 30})

# 列出标签页
resp = requests.get(f"{BASE}/api/profiles/agent-1/tabs", params={"token": TOKEN})
print(resp.json())
```

## 从源码编译

### 前置条件

- [Go](https://go.dev/dl/) 1.23+

### 编译

```bash
# Windows（GUI 应用，无控制台窗口）
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# Linux
CGO_ENABLED=0 go build -o cdpclient .

# Windows 交叉编译 Linux
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o cdpclient-linux-amd64 .
```

## 许可证

MIT
