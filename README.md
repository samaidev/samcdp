# SamCDP

Remote Chrome CDP (Chrome DevTools Protocol) debugging proxy via [aitun](https://aitun.cc) tunnel.

Expose your local Chrome browser's CDP debugging port as a public HTTPS endpoint, enabling remote AI agents to control and automate Chrome from anywhere.

[中文文档 (Chinese README)](README_cn.md)

---

## Features

- **Multi-Profile Chrome Management** — Run multiple isolated Chrome instances (profiles) simultaneously, each with its own user-data-dir and debugging port
- **Single Tunnel + Path Routing** — One aitun tunnel routes traffic to all Chrome profiles via path-based routing (`/profile-name/json/list`)
- **CDP Management API** — RESTful API to start/stop Chrome, list/open/close tabs, and proxy raw CDP commands
- **System Tray Integration** — Native system tray icon with run/stop/settings menu (Windows & Linux)
- **Auto-Cleanup** — Kills stale Chrome processes on startup to prevent window accumulation
- **Duration-Based Auto-Shutdown** — Start Chrome with a time limit; auto-closes when expired
- **Login Persistence** — Chrome user-data-dir preserves cookies/sessions across restarts
- **Cross-Platform** — Builds for Windows and Linux (amd64)

## Architecture

```
Remote Agent
    │
    ▼ HTTPS
┌───────────────────────────────┐
│  aitun Tunnel (public URL)   │  e.g. https://mos.t.aitun.cc
└───────────┬───────────────────┘
            │
            ▼
┌───────────────────────────────┐
│  SamCDP CDP Proxy (port 9333) │  Path router + Management API
└───┬───────────┬───────────┬───┘
    │           │           │
    ▼           ▼           ▼
 Chrome:9334 Chrome:9335 Chrome:9336
 (agent-1)   (agent-2)   (agent-3)
```

## Quick Install

### Linux (bash)

```bash
# One-line install
curl -fsSL https://raw.githubusercontent.com/samaidev/samcdp/main/install.sh | bash
```

<details>
<summary>Manual install (Linux)</summary>

```bash
# Download and build from source
git clone https://github.com/samaidev/samcdp.git
cd samcdp
CGO_ENABLED=0 go build -o cdpclient .

# Download aitun-client
wget -O aitun-client https://aitun.cc/downloads/aitun-client-linux-amd64
chmod +x aitun-client

# Run
export DISPLAY=:1
./cdpclient
```
</details>

### Windows (PowerShell)

```powershell
# One-line install
irm https://raw.githubusercontent.com/samaidev/samcdp/main/install.ps1 | iex
```

<details>
<summary>Manual install (Windows)</summary>

```powershell
# Clone and build
git clone https://github.com/samaidev/samcdp.git
cd samcdp
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# Run
.\cdpclient.exe
```
</details>

## Configuration

On first launch, SamCDP creates a config file at:
- **Windows:** `%APPDATA%\cdpclient\config.json`
- **Linux:** `~/.config/cdpclient/config.json`

```json
{
  "aitun_key": "ait_your_aitun_key_here",
  "auth_token": "auto_generated_token",
  "lang": "en",
  "profiles": [
    {"name": "agent-1", "port": 9334, "enabled": true},
    {"name": "agent-2", "port": 9335, "enabled": true},
    {"name": "agent-3", "port": 9336, "enabled": true}
  ]
}
```

### Getting an aitun Key

1. Register at [aitun.cc](https://aitun.cc)
2. Verify your email
3. Register a subdomain and get your key (`ait_xxxx`)

## API Reference

All endpoints require authentication via `?token=YOUR_TOKEN` query param or `Authorization: Bearer YOUR_TOKEN` header.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/?token=xxx` | API docs page |
| `GET` | `/api/profiles?token=xxx` | List all profiles |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=MINUTES` | Start Chrome (duration required, in minutes) |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | Stop Chrome |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | List browser tabs |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&token=xxx` | Open a new tab |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | Close a tab |
| — | `/<profile>/json/*` | Raw CDP protocol passthrough |

### Example: Start Chrome and Automate

```bash
# Start Chrome for agent-1 (30 minute limit)
curl -X POST "https://YOUR_TUNNEL_URL/api/profiles/agent-1/start?token=YOUR_TOKEN&duration=30"

# List tabs
curl "https://YOUR_TUNNEL_URL/api/profiles/agent-1/tabs?token=YOUR_TOKEN"

# Open DeepSeek
curl -X POST "https://YOUR_TUNNEL_URL/api/profiles/agent-1/tabs/new?url=https://chat.deepseek.com&token=YOUR_TOKEN"

# Connect via CDP WebSocket
wss://YOUR_TUNNEL_URL/agent-1/json/version
```

### Python Example

```python
import requests

BASE = "https://YOUR_TUNNEL_URL"
TOKEN = "YOUR_TOKEN"

# Start Chrome (30 min)
requests.post(f"{BASE}/api/profiles/agent-1/start", params={"token": TOKEN, "duration": 30})

# List tabs
resp = requests.get(f"{BASE}/api/profiles/agent-1/tabs", params={"token": TOKEN})
print(resp.json())
```

## Building from Source

### Prerequisites

- [Go](https://go.dev/dl/) 1.23+

### Build

```bash
# Windows (GUI app, no console window)
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# Linux
CGO_ENABLED=0 go build -o cdpclient .

# Cross-compile Linux from Windows
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o cdpclient-linux-amd64 .
```

## License

MIT
