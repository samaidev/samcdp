# SamCDP

Remote Chrome automation for AI agents. Expose local Chrome browsers as public HTTPS endpoints via [aitun](https://aitun.cc) tunnel, enabling remote AI agents to control and automate Chrome from anywhere.

[中文文档](README_cn.md)

---

## Features

- **Multi-Profile Chrome Management** — Run multiple isolated Chrome instances simultaneously, each with its own login session, cookies, and debugging port
- **One Public URL for All Profiles** — Single tunnel serves all Chrome instances via path routing (`/agent-1/json/list`, `/agent-2/json/list`, etc.)
- **RESTful Management API** — Start/stop Chrome, list/open/close tabs, proxy raw CDP commands — all via HTTP
- **Android Version (APK)** — Turn an Android phone into a remote WebView automation endpoint with the same REST API ([Releases](https://github.com/samaidev/samcdp/releases))
- **Login Persistence** — Chrome user-data-dir preserves cookies and sessions across restarts; no need to re-login after restart
- **Auto-Cleanup** — Automatically kills stale Chrome processes on startup to prevent window accumulation
- **Duration-Based Auto-Shutdown** — Start Chrome with a time limit; auto-closes when expired
- **System Tray Integration** — Native tray icon with run/stop/settings menu
- **Cross-Platform** — Windows, Linux, macOS, Android

## Screenshot

![Settings (EN)](docs/settings-en.png)

## Quick Install

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/samaidev/samcdp/main/install.sh | bash
```

### Windows (PowerShell)

```powershell
irm https://raw.githubusercontent.com/samaidev/samcdp/main/install.ps1 | iex
```

> macOS note: The system tray requires CGO. Run `CGO_ENABLED=1 go build` if building from source on macOS.

## Usage

### 1. Get an aitun Key

Register at [aitun.cc](https://aitun.cc), verify your email, and register a subdomain to get a key (`ait_xxxx`).

### 2. Configure

Launch SamCDP — a system tray icon will appear. Right-click the tray icon and select **Settings** to open the settings page:

- Enter your **aitun Key**
- Manage **Profiles** (add/remove/toggle)
- View your **Remote URL** and **Auth Token**

Alternatively, edit the config file directly:

- **Windows:** `%APPDATA%\cdpclient\config.json`
- **Linux:** `~/.config/cdpclient/config.json`
- **macOS:** `~/Library/Application Support/cdpclient/config.json`

```json
{
  "aitun_key": "ait_your_key_here",
  "auth_token": "auto_generated",
  "lang": "en",
  "profiles": [
    {"name": "agent-1", "port": 9334, "enabled": true},
    {"name": "agent-2", "port": 9335, "enabled": true},
    {"name": "agent-3", "port": 9336, "enabled": true}
  ]
}
```

### 3. Start the Service

Click **Run** in the tray menu, or SamCDP auto-starts on launch. The tunnel URL will appear in the tray menu (e.g. `https://mos.t.aitun.cc`).

### 4. Use the API

All endpoints require authentication via `?token=YOUR_TOKEN` or `Authorization: Bearer YOUR_TOKEN`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/?token=xxx` | API docs page |
| `GET` | `/api/profiles?token=xxx` | List all profiles |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=MINUTES` | Start Chrome (duration required) |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | Stop Chrome |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | List browser tabs |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&token=xxx` | Open a new tab |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | Close a tab |
| — | `/<profile>/json/*` | Raw CDP protocol passthrough |

#### Example: Start Chrome and Automate

```bash
# Start Chrome for agent-1 (30 minute limit)
curl -X POST "https://YOUR_URL/api/profiles/agent-1/start?token=YOUR_TOKEN&duration=30"

# Open DeepSeek
curl -X POST "https://YOUR_URL/api/profiles/agent-1/tabs/new?url=https://chat.deepseek.com&token=YOUR_TOKEN"

# List tabs
curl "https://YOUR_URL/api/profiles/agent-1/tabs?token=YOUR_TOKEN"

# Connect via CDP WebSocket
wss://YOUR_URL/agent-1/json/version
```

#### Python Example

```python
import requests

BASE = "https://YOUR_URL"
TOKEN = "YOUR_TOKEN"

# Start Chrome (30 min)
requests.post(f"{BASE}/api/profiles/agent-1/start", params={"token": TOKEN, "duration": 30})

# List tabs
resp = requests.get(f"{BASE}/api/profiles/agent-1/tabs", params={"token": TOKEN})
print(resp.json())
```

## Android Version (APK)

**SamCDP-Android** turns an Android phone into a remote WebView automation endpoint. The on-device REST API is 1:1 compatible with the desktop version — profiles map to isolated headless WebView sessions instead of Chrome instances.

![Android App](android/docs/android-app.png)
![Baidu tab rendered headless on phone](android/docs/android-tab-baidu.png)

### Install

Download `SamCDP-android-vX.Y.Z-debug.apk` from the [Releases page](https://github.com/samaidev/samcdp/releases) (requires Android 8.0+ / API 26), sideload it (allow "install unknown apps"), open the app and tap **Run**. The app auto-starts the API server and shows its URL + token on screen.

### Android API surface

Identical to desktop, plus mobile-specific extras:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/health?token=xxx` | Health probe |
| `GET` | `/api/status?token=xxx` | Server status (ip/port/uptime/running profiles) |
| `GET` | `/api/profiles?token=xxx` | List all profiles |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=MIN` | Start profile (duration required) |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | Stop profile (cookies persisted) |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | List tabs |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&token=xxx&ua=AGENT` | Open tab (headless WebView, `ua` optional) |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | Close a tab |
| `GET` | `/api/profiles/<name>/tabs/<id>/screenshot?token=xxx` | PNG screenshot of the tab |
| `POST` | `/api/profiles/<name>/tabs/<id>/eval?token=xxx` | Run JS in the tab, body `{"js":"..."}` |
| `GET` | `/<profile>/json/version` · `/<profile>/json/list` | CDP-style passthrough |

Notes:
- Tabs render headless (1080x2340) with a desktop Chrome User-Agent by default; pass `&ua=...` to override
- Non-http(s) navigations (deep links like `baiduboxapp://`) are blocked to prevent hijacks
- Cookie jars are snapshotted per profile on stop and restored on start (login persistence)
- `duration` triggers auto-stop, mirroring desktop behavior

### Build the APK from source

```bash
# dependency-free toolchain: build-tools 34 (aapt2/d8/apksigner) + platform-34 + ECJ
SDK_DIR=/path/to/sdk android/build.sh
# → android/build/SamCDP-android-vX.Y.Z-debug.apk
```

See [android/README.md](android/README.md) for details.

## Download Pre-built Binaries

Download from the [Releases page](https://github.com/samaidev/samcdp/releases):

| Platform | File |
|----------|------|
| Windows | `cdpclient-windows-amd64.exe` + `aitun-client-windows-amd64.exe` |
| Linux | `cdpclient-linux-amd64` + `aitun-client-linux-amd64` |
| macOS | `cdpclient-darwin-amd64` + `aitun-client-darwin-amd64` |

Place `aitun-client` in the same directory as `cdpclient`, then run `cdpclient`.

## Build from Source

```bash
# Windows
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# Linux
CGO_ENABLED=0 go build -o cdpclient .

# macOS (requires CGO for system tray)
CGO_ENABLED=1 go build -o cdpclient .
```

## License

MIT
