# SamCDP

远程 Chrome 自动化工具，专为 AI 智能体设计。通过 [aitun](https://aitun.cc) 隧道将本地 Chrome 浏览器暴露为公网 HTTPS 地址，让远程智能体可以随时控制和自动化操作 Chrome。

[English README](README.md)

---

## 功能特性

- **多 Profile Chrome 管理** — 同时运行多个独立 Chrome 实例，各自拥有独立的登录状态、Cookie 和调试端口
- **一个公网地址管所有 Profile** — 单条隧道通过路径路由服务所有 Chrome 实例（`/agent-1/json/list`、`/agent-2/json/list` 等）
- **RESTful 管理 API** — 通过 HTTP 启动/停止 Chrome、列出/打开/关闭标签页、透传原生 CDP 协议
- **Android 版本（APK）** — 把安卓手机变成远程 WebView 自动化端点，REST API 与桌面版 1:1 兼容（见 [Releases](https://github.com/samaidev/samcdp/releases)）
- **登录状态持久化** — Chrome user-data-dir 保留 Cookie 和 session，重启后无需重新登录
- **自动清理** — 启动时自动杀掉残留 Chrome 进程，防止窗口堆积
- **超时自动关闭** — 启动 Chrome 时可指定运行时长（分钟），到期自动关闭
- **系统托盘集成** — 原生托盘图标，支持运行/停止/设置菜单
- **跨平台** — 支持 Windows、Linux、macOS、Android

## 截图

![设置页面](docs/settings-zh.png)

## 一键安装

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/samaidev/samcdp/main/install.sh | bash
```

### Windows（PowerShell）

```powershell
irm https://raw.githubusercontent.com/samaidev/samcdp/main/install.ps1 | iex
```

> macOS 注意：系统托盘需要 CGO 编译。从源码编译时请使用 `CGO_ENABLED=1 go build`。

## 使用方法

### 1. 获取 aitun 密钥

在 [aitun.cc](https://aitun.cc) 注册账号，验证邮箱后注册子域名，获取密钥（`ait_xxxx`）。

### 2. 配置

启动 SamCDP，系统托盘会出现图标。右键点击托盘图标，选择**设置**打开设置页面：

- 填入 **aitun Key**
- 管理 **Profile**（新增/删除/启用切换）
- 查看你的**远程地址**和**鉴权 Token**

也可以直接编辑配置文件：

- **Windows:** `%APPDATA%\cdpclient\config.json`
- **Linux:** `~/.config/cdpclient/config.json`
- **macOS:** `~/Library/Application Support/cdpclient/config.json`

```json
{
  "aitun_key": "ait_你的密钥",
  "auth_token": "自动生成",
  "lang": "zh",
  "profiles": [
    {"name": "agent-1", "port": 9334, "enabled": true},
    {"name": "agent-2", "port": 9335, "enabled": true},
    {"name": "agent-3", "port": 9336, "enabled": true}
  ]
}
```

### 3. 启动服务

在托盘菜单点击**运行**，或启动后自动运行。隧道地址会显示在托盘菜单中（如 `https://mos.t.aitun.cc`）。

### 4. 调用 API

所有接口需要鉴权：通过 `?token=YOUR_TOKEN` 参数或 `Authorization: Bearer YOUR_TOKEN` 请求头。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/?token=xxx` | API 文档页面 |
| `GET` | `/api/profiles?token=xxx` | 列出所有 profile |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=分钟` | 启动 Chrome（duration 必填） |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | 停止 Chrome |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | 列出标签页 |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&close_after=秒数&token=xxx` | 打开新标签页（**`close_after` 必填**：N 秒后自动关闭，1..604800） |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | 关闭标签页 |
| — | `/<profile>/json/*` | CDP 原生协议透传 |

#### 示例：启动 Chrome 并自动化操作

```bash
# 启动 agent-1 的 Chrome（30 分钟超时）
curl -X POST "https://你的地址/api/profiles/agent-1/start?token=你的Token&duration=30"

# 打开 DeepSeek（close_after 必填：3600 秒 = 1 小时后自动关闭）
curl -X POST "https://你的地址/api/profiles/agent-1/tabs/new?url=https://chat.deepseek.com&close_after=3600&token=你的Token"

# 列出标签页
curl "https://你的地址/api/profiles/agent-1/tabs?token=你的Token"

# 通过 CDP WebSocket 连接
wss://你的地址/agent-1/json/version
```

#### Python 示例

```python
import requests

BASE = "https://你的地址"
TOKEN = "你的Token"

# 启动 Chrome（30 分钟）
requests.post(f"{BASE}/api/profiles/agent-1/start", params={"token": TOKEN, "duration": 30})

# 打开标签页 —— close_after（秒）必填，到期标签自动关闭回收内存
resp = requests.post(f"{BASE}/api/profiles/agent-1/tabs/new",
                     params={"token": TOKEN, "url": "https://chat.deepseek.com", "close_after": 3600})
print(resp.json())

# 列出标签页
resp = requests.get(f"{BASE}/api/profiles/agent-1/tabs", params={"token": TOKEN})
print(resp.json())
```

## Android 版本（APK）

**SamCDP-Android** 把安卓手机变成远程 WebView 自动化端点。手机端 REST API 与桌面版 1:1 兼容 —— Profile 对应隔离的 headless WebView 会话（替代桌面版 Chrome 实例）。

![安卓应用](android/docs/android-app.png)
![手机 headless 渲染的百度](android/docs/android-tab-baidu.png)

### 亮点（相对桌面版）

- **内置 aitun 隧道，双模式** — 临时代理（免费自动分配 URL，24h）/ 子域名（绑定 `ait_` Key 的固定域名）。隧道客户端（`libaitunclient.so`，与 SamDocker 同款）打包在 APK 内并从 `nativeLibraryDir` 执行；因 Go 静态二进制读不到安卓 DNS，App 会先用系统解析器解析 `aitun.cc` 再以 `-s IP:6639` 直连
- **真·多 Cookie 存储池** — 每个 Profile 运行在独立安卓进程（`:cdp1`…`:cdp6`），拥有独立 WebView 数据目录后缀，Cookie 完全隔离；停止时快照到独立存储、启动时恢复
- **重启记忆（v1.4.1）** — 通过手机开关启动的 Profile（或 `duration=0`）会记为自启动，下次打开 App 时全部自动恢复；没有任何已保存记录时（首次启动）默认自动激活第一个 Profile；API 以 `duration>0` 启动的 Profile 仍是临时的、不会被恢复；上一会话遗留的孤儿 runner 进程会被探测并清理
- **桌面级设置界面** — 与桌面设置页相同的暖橙奶油配色与分区；桌面端 Logo（橙色 C）作为启动图标
- **电池优化按钮**（参考 SamDocker）— 申请忽略电池优化，保证后台隧道/服务存活
- **远程 Profile 管理** — `POST /api/profiles/add?name=x`、`DELETE /api/profiles/<name>`

### 安装

从 [Releases 页面](https://github.com/samaidev/samcdp/releases) 下载 `samcdp-android.apk`（需要 Android 8.0+ / API 26），允许"安装未知应用"后侧载安装，打开 App 点击**运行**。App 会自动启动 API 服务器，并在界面上显示访问地址和 Token。

> MIUI/HyperOS 提示：如通过 `adb` 安装，需在开发者选项中开启"USB 安装"。

### 安卓端 API

与桌面版一致，另有移动端专属接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health?token=xxx` | 健康检查 |
| `GET` | `/api/status?token=xxx` | 服务器状态（含隧道地址） |
| `GET` | `/api/tunnel?token=xxx` | aitun 隧道状态 |
| `POST` | `/api/tunnel/start?mode=temp&token=xxx` | 启动临时代理隧道 |
| `POST` | `/api/tunnel/start?mode=subdomain&key=ait_xxx&token=xxx` | 启动子域名隧道 |
| `POST` | `/api/tunnel/stop?token=xxx` | 停止隧道 |
| `GET` | `/api/profiles?token=xxx` | 列出 Profile |
| `POST` | `/api/profiles/add?name=x&token=xxx` | 新增 Profile（最多 6 个，各占独立进程） |
| `DELETE` | `/api/profiles/<name>?token=xxx` | 删除 Profile |
| `POST` | `/api/profiles/<name>/start?token=xxx&duration=分钟` | 启动（独立进程 Cookie 池） |
| `POST` | `/api/profiles/<name>/stop?token=xxx` | 停止（Cookie 持久化） |
| `GET` | `/api/profiles/<name>/tabs?token=xxx` | 列出标签页（含 `auto_close_in_s` 剩余存活秒数） |
| `POST` | `/api/profiles/<name>/tabs/new?url=URL&close_after=秒数&ua=AGENT&token=xxx` | 打开标签页（**`close_after` 必填**：N 秒后自动关闭） |
| `DELETE` | `/api/profiles/<name>/tabs/<id>?token=xxx` | 关闭标签页 |
| `GET` | `/api/profiles/<name>/tabs/<id>/screenshot?token=xxx` | 标签页 PNG 截图 |
| `POST` | `/api/profiles/<name>/tabs/<id>/eval?token=xxx` | 在标签页内执行 JS |
| `GET` | `/<profile>/json/version` · `/<profile>/json/list` | CDP 风格透传 |
| `GET` | `/api/downloads?token=xxx` | 全部 Profile 的下载文件（运行中实时代理，未运行读磁盘） |
| `GET` | `/api/profiles/<name>/downloads?token=xxx` | 列出该 Profile 的 WebView 下载文件 |
| `GET` | `/api/profiles/<name>/downloads/<id>/file?token=xxx` | 拉取已下载文件（原始字节） |
| `DELETE` | `/api/profiles/<name>/downloads/<id>?token=xxx` | 删除已下载文件 |
| `POST` | `/api/profiles/<name>/downloads/<id>/upload?tab=ID&selector=CSS&token=xxx` | 把已下载文件交给网页的 `<input type=file>`（作为附件上传） |

说明：
- **`tabs/new` 的 `close_after` 为必填参数** —— 整数秒（1..604800），到期标签自动销毁并回收内存，如 `close_after=3600` = 1 小时；不传或非法值返回 HTTP 400 并附自解释错误信息。设计目的：强制智能体声明标签存活时长，防止开大量标签不关耗尽内存；人在手机 UI 上手动开标签（➕ / 显示WebView / 页面弹窗）默认 1 小时自动关闭。`GET /tabs` 每个标签返回 `auto_close_in_s`（剩余存活秒数）
- **下载文件（v1.4.2）** —— WebView 内发生的下载（直链、`blob:` 导出、`data:` URL）都会自动捕获到该 Profile 的私有存储，不会写入手机的公共下载目录。用 `GET .../downloads` 列出（字段：id、name、size、state、url），用 `GET .../downloads/<id>/file` 拉取字节，`DELETE .../downloads/<id>` 删除。要把文件作为**附件**上传到网站（如从 A 站导出后传到 B 站）：打开目标标签页后调 `POST .../downloads/<id>/upload?tab=<tabId>&selector=<css>` —— 文件会交给页面上第一个匹配的 `<input type=file>`，页面随即走自己的上传流程（若不自动提交，用 `/eval` 自行提交表单）。注意标签页必须存活（记得 `close_after`），且 selector 必须指向当前页面上的真实文件输入框。手机端 WebView 顶栏有 ⬇ 按钮可查看这些文件（点标题打开、🗑 删除）
- 标签页默认以桌面 Chrome UA 渲染（1080x2340 headless），可用 `&ua=...` 覆盖
- 非 http(s) 跳转（如 `baiduboxapp://` 深链）会被拦截，防止劫持
- `duration` 到期自动关闭，与桌面版一致

### 从源码构建 APK

```bash
# 零依赖工具链：build-tools 34（aapt2/d8/apksigner）+ platform-34 + ECJ
SDK_DIR=/path/to/sdk android/build.sh
# → android/build/SamCDP-android-vX.Y.Z-debug.apk
```

详见 [android/README.md](android/README.md)。

## 下载预编译二进制

从 [Releases 页面](https://github.com/samaidev/samcdp/releases) 下载 —— 每个桌面产物都已内嵌 aitun 隧道客户端（v1.4.7 起无需单独下载）：

| 平台 | 文件 |
|------|------|
| Windows | `samcdp-windows-amd64.exe` |
| Linux | `samcdp-linux-amd64` |
| macOS（Intel） | `samcdp-darwin-amd64` |
| macOS（Apple Silicon） | `samcdp-darwin-arm64` |
| Android | `samcdp-android.apk` |

一个平台一个文件，文件名固定不带版本号（版本看 Release tag），没有重复/别名文件。

## CI / 发布

构建与发布由 GitHub Actions 自动完成：

- **CI**（`.github/workflows/ci.yml`）—— 每次 push / PR 到 `main` 自动跑 `go vet` + 编译冒烟。
- **发布**（`.github/workflows/release.yml`）—— 推送 annotated tag `v*` 自动构建全部产物（Windows/Linux/darwin 桌面 + Android APK）并创建 GitHub Release：

  ```bash
  git tag -a v1.4.10 --cleanup=verbatim -m "发布说明写在 tag 里"   # --cleanup=verbatim 保留 ## 开头的标题行
  git push origin v1.4.10
  ```

  tag 注释即发布说明（CI 通过 API 读取 tag 对象注释）。Actions 页面的 `workflow_dispatch` 可做只构建不发布的干跑。Android 构建使用仓库内 `android/samcdp-debug.keystore` 签名，升级安装不会因签名变化被拒。

## 从源码编译

```bash
# Windows
go build -ldflags "-H windowsgui" -o cdpclient.exe .

# Linux
CGO_ENABLED=0 go build -o cdpclient .

# macOS（系统托盘需要 CGO）
CGO_ENABLED=1 go build -o cdpclient .
```

## 许可证

MIT
