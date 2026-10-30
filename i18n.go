package main

// i18n.go — 中英文双语支持（v1.4.0: 默认英文，设置页/托盘均可切换）

import "fmt"

type Lang string

const (
	LangZH Lang = "zh"
	LangEN Lang = "en"
)

var currentLang = LangEN // v1.4.0: English is the default language

var i18nMap = map[string]map[Lang]string{
	// 托盘菜单
	"tray.run":        {"zh": "运行", "en": "Run"},
	"tray.stop":       {"zh": "停止", "en": "Stop"},
	"tray.lang":       {"zh": "EN", "en": "中文"},
	"tray.autoStart":  {"zh": "开机启动", "en": "Start at Boot"},
	"tray.cancelAuto": {"zh": "取消开机启动", "en": "Cancel Boot Start"},
	"tray.cdpAddr":    {"zh": "复制地址", "en": "Copy URL"},
	"tray.settings":   {"zh": "设置", "en": "Settings"},
	"tray.quit":       {"zh": "退出", "en": "Quit"},

	// 设置页面（通用）
	"settings.title":      {"zh": "samcdp 设置", "en": "samcdp Settings"},
	"settings.aitunKey":   {"zh": "Aitun Key (留空则用临时域名)", "en": "Aitun Key (empty = temp domain)"},
	"settings.save":       {"zh": "保存", "en": "Save"},
	"settings.domain":     {"zh": "当前域名", "en": "Current Domain"},
	"settings.noDomain":   {"zh": "未运行", "en": "Not Running"},
	"settings.cdpAddr":    {"zh": "CDP 地址", "en": "CDP Address"},
	"settings.remoteAddr": {"zh": "远程地址", "en": "Remote Address"},
	"settings.token":      {"zh": "访问 Token", "en": "Access Token"},
	"settings.regenerate": {"zh": "重新生成", "en": "Regenerate"},
	"settings.tokenHint":  {"zh": "访问远程地址时需要携带此 Token 鉴权", "en": "This token is required for remote access authentication"},
	"settings.status":     {"zh": "状态", "en": "Status"},
	"settings.running":    {"zh": "运行中", "en": "Running"},
	"settings.stopped":    {"zh": "已停止", "en": "Stopped"},
	"settings.saved":      {"zh": "已保存!", "en": "Saved!"},
	"settings.tokenRegen": {"zh": "Token 已重新生成!", "en": "Token regenerated!"},
	"settings.keyHint":    {"zh": "在 aitun.cc 注册获取 Key（可固定子域名）", "en": "Get your key at aitun.cc (for fixed subdomain)"},

	// Profile 管理
	"settings.profiles":       {"zh": "浏览器 Profile", "en": "Browser Profiles"},
	"settings.profile":        {"zh": "Profile", "en": "Profile"},
	"settings.addProfile":     {"zh": "+ 新增 Profile", "en": "+ Add Profile"},
	"settings.profileName":    {"zh": "Profile 名称", "en": "Profile Name"},
	"settings.port":           {"zh": "端口", "en": "Port"},
	"settings.delete":         {"zh": "删除", "en": "Delete"},
	"settings.copy":           {"zh": "复制地址", "en": "Copy URL"},
	"settings.noProfiles":     {"zh": "暂无 Profile", "en": "No Profiles"},
	"settings.profileAdded":   {"zh": "Profile 已添加", "en": "Profile added"},
	"settings.profileDeleted": {"zh": "Profile 已删除", "en": "Profile deleted"},

	// 提示
	"toast.copied":         {"zh": "已复制带Token的远程地址到剪切板", "en": "Remote address with token copied to clipboard"},
	"toast.copyFailed":     {"zh": "复制失败: ", "en": "Copy failed: "},
	"toast.notRunning":     {"zh": "请先启动服务", "en": "Please start the service first"},
	"toast.chromeNotFound": {"zh": "未找到 Chrome，请安装: https://www.google.com/chrome/", "en": "Chrome not found, install: https://www.google.com/chrome/"},
	"toast.started":        {"zh": "CDP 服务已启动", "en": "CDP service started"},
	"toast.stopped":        {"zh": "CDP 服务已停止", "en": "CDP service stopped"},
	"toast.startFailed":    {"zh": "启动失败: ", "en": "Start failed: "},
	"toast.autoStartOn":    {"zh": "已设置开机启动", "en": "Boot start enabled"},
	"toast.autoStartOff":   {"zh": "已取消开机启动", "en": "Boot start disabled"},

	// 托盘 tooltip
	"tray.tooltip":        {"zh": "samcdp", "en": "samcdp"},
	"tray.tooltipRunning": {"zh": "samcdp (运行中)", "en": "samcdp (Running)"},

	// 设置页（HTML）静态文本 — settings_page.go
	"sp.remoteH":        {"zh": "远程地址（aitun 隧道）", "en": "Remote Address (aitun tunnel)"},
	"sp.lanH":           {"zh": "局域网地址", "en": "LAN Address"},
	"sp.lanHint":        {"zh": "同一局域网内的设备可直接访问（隧道不可用时的备用入口）", "en": "Directly reachable from devices on the same LAN (fallback when the tunnel is unavailable)"},
	"sp.tunnelH":        {"zh": "aitun 隧道", "en": "aitun Tunnel"},
	"sp.keyPlaceholder": {"zh": "留空则使用临时代理（随机地址）", "en": "Empty = temp proxy (random address)"},
	"sp.keyHint":        {"zh": "在 aitun.cc 注册获取；填写后用固定子域名，留空用临时代理。保存后立即生效（自动重启隧道）", "en": "Register at aitun.cc; a Key gives a fixed subdomain, empty uses the temp proxy. Saved changes take effect immediately (tunnel auto-restarts)"},
	"sp.curMode":        {"zh": "当前模式：", "en": "Current mode: "},
	"sp.statusH":        {"zh": "运行状态", "en": "Status"},
	"sp.profilesH":      {"zh": "Profile 管理", "en": "Profiles"},
	"sp.tokenH":         {"zh": "Token", "en": "Token"},
	"sp.tokenHint":      {"zh": "远程 API 鉴权用，格式: ?token=xxx", "en": "Token for the remote API, format: ?token=xxx"},
	"sp.saveBtn":        {"zh": "保存设置", "en": "Save Settings"},
	"sp.langLabel":      {"zh": "Language / 语言", "en": "Language / 语言"},
	"sp.copy":           {"zh": "复制", "en": "Copy"},
	"sp.fetching":       {"zh": "获取中...", "en": "Loading..."},
	"sp.tunnelOff":      {"zh": "隧道未启用", "en": "Tunnel not enabled"},
	"sp.notStarted":     {"zh": "未启动", "en": "Not started"},

	// 设置页（HTML）JS 动态文本
	"sp.jsSaved":          {"zh": "已保存!", "en": "Saved!"},
	"sp.jsSaveFail":       {"zh": "保存失败", "en": "Save failed"},
	"sp.jsSavedButTunnel": {"zh": "，但隧道重启失败: ", "en": ", but the tunnel restart failed: "},
	"sp.jsSavedTunnel":    {"zh": "，隧道已按新模式重启", "en": ", tunnel restarted with the new mode"},
	"sp.jsRegenConfirm":   {"zh": "重新生成 Token？旧地址将立即失效。", "en": "Regenerate the token? Old URLs stop working immediately."},
	"sp.jsRegenDone":      {"zh": "Token 已重新生成!", "en": "Token regenerated!"},
	"sp.jsNamePrompt":     {"zh": "输入 Profile 名称（如 agent-3）:", "en": "Profile name (e.g. agent-3):"},
	"sp.jsAdded":          {"zh": "已添加 ", "en": "Added "},
	"sp.jsAddFail":        {"zh": "添加失败", "en": "Add failed"},
	"sp.jsDelConfirm":     {"zh": "删除 Profile \"", "en": "Delete profile \""},
	"sp.jsDelConfirmEnd":  {"zh": "\"？", "en": "\"?"},
	"sp.jsDeleted":        {"zh": "已删除 ", "en": "Deleted "},
	"sp.jsUrlNotReady":    {"zh": "地址未就绪", "en": "Address not ready"},
	"sp.jsCopied":         {"zh": "已复制: ", "en": "Copied: "},
	"sp.jsCopiedShort":    {"zh": "已复制", "en": "Copied"},
	"sp.jsCopyFailHint":   {"zh": "复制失败，请手动选中地址复制", "en": "Copy failed — select the address and copy manually"},
	"sp.jsRunningN":       {"zh": "运行中 (", "en": "Running ("},
	"sp.jsStopped":        {"zh": "已停止", "en": "Stopped"},
	"sp.jsConnecting":     {"zh": "隧道连接中…（上线后自动显示公网地址）", "en": "Tunnel connecting… (public URL appears once online)"},
	"sp.jsSvcStopped":     {"zh": "服务未启动", "en": "Service stopped"},
	"sp.jsTunnelOnline":   {"zh": "隧道在线", "en": "Tunnel online"},
	"sp.jsTunnelConn":     {"zh": "隧道连接中…", "en": "Tunnel connecting…"},
	"sp.jsModeSub":        {"zh": "子域名（固定地址）", "en": "Subdomain (fixed address)"},
	"sp.jsModeTemp":       {"zh": "临时代理（随机地址）", "en": "Temp proxy (random address)"},
	"sp.jsProfRunning":    {"zh": "● 运行中", "en": "● Running"},
	"sp.jsProfStopped":    {"zh": "○ 已停止", "en": "○ Stopped"},
	"sp.jsProfDisabled":   {"zh": "(已禁用)", "en": "(disabled)"},
	"sp.jsDelete":         {"zh": "删除", "en": "Delete"},
	"sp.jsProfilesN":      {"zh": " profiles)", "en": " profiles)"},

	// v1.4.9 模式透明化：实际地址形式 + 与 Key 设置的一致性
	"sp.liveForm":        {"zh": "实际地址形式：", "en": "Live address form: "},
	"sp.formPending":     {"zh": "隧道未上线，暂无实际地址", "en": "Tunnel not online — no live address yet"},
	"sp.formShort":       {"zh": "临时代理短链（aitun.cc/xxxx）", "en": "temp-proxy short URL (aitun.cc/xxxx)"},
	"sp.formSub":         {"zh": "子域名（固定形式）", "en": "subdomain URL (fixed form)"},
	"sp.formMatch":       {"zh": "✓ 与 Key 设置一致", "en": "✓ matches the Key setting"},
	"sp.formMismatch":    {"zh": "⚠ 与 Key 设置不一致", "en": "⚠ does NOT match the Key setting"},
	"sp.reuseNote":       {"zh": "（正在复用 24h 内的持久化身份，以保证地址稳定）", "en": "(reusing the persisted identity within 24h to keep the URL stable)"},
	"sp.warnNoKeySub":    {"zh": "未设置 Key，但实际地址是子域名形式：客户端复用了持久化身份（可能是本机其他 aitun 应用写入，或旧身份残留）。地址当前可用；如需全新临时代理地址，请停止程序、删除下面目录中的 .aitun 文件夹后重新启动：", "en": "No Key is set, but the live address is a subdomain URL: the client is reusing a persisted identity (possibly written by another aitun app on this machine, or a stale one). The address works; for a fresh temp-proxy URL, stop the app, delete the .aitun folder inside the directory below, then start again: "},
	"sp.warnKeySetShort": {"zh": "已设置 Key，但实际地址仍是临时代理短链：隧道可能仍在连接中，或旧身份尚未切换。点击保存会清除旧身份并按 Key 重启隧道。", "en": "A Key is set, but the live address is still a temp-proxy short URL: the tunnel may still be connecting, or a stale identity has not switched yet. Saving clears the stale identity and restarts the tunnel with the Key."},

	// API 文档页 — proxy_docs.go
	"docs.needAuth":    {"zh": "需要鉴权才能访问", "en": "Authentication required"},
	"docs.addToken":    {"zh": "在 URL 后添加", "en": "Append to the URL:"},
	"docs.orHeader":    {"zh": "或设置请求头", "en": "Or set the header:"},
	"docs.subtitle":    {"zh": "远程 Chrome CDP 管理接口 · 单隧道多 Profile 架构", "en": "Remote Chrome CDP management API · single tunnel, multi-profile"},
	"docs.authH":       {"zh": "鉴权方式", "en": "Authentication"},
	"docs.authParam":   {"zh": "在 URL 查询参数中传递 token", "en": "Pass the token in the URL query string"},
	"docs.authHeader":  {"zh": "在请求头中传递 token", "en": "Pass the token in the request header"},
	"docs.profilesH":   {"zh": "Profile 管理", "en": "Profile Management"},
	"docs.dListProf":   {"zh": "列出所有 profile 及运行状态、标签页数量", "en": "List all profiles with run status and tab counts"},
	"docs.dCreateProf": {"zh": "创建新 profile（自动分配端口）", "en": "Create a new profile (port auto-assigned)"},
	"docs.dDelProf":    {"zh": "删除指定 profile（停止 Chrome + 移除配置）", "en": "Delete a profile (stops Chrome + removes config)"},
	"docs.dStart":      {"zh": "启动指定 profile 的 Chrome。必须指定运行时长，到期后自动关闭 Chrome 防止窗口堆积", "en": "Start a profile's Chrome. A run duration is REQUIRED; Chrome auto-closes when it expires to prevent window piles"},
	"docs.dStartWarn":  {"zh": "⚠ duration 为必填参数（单位：分钟），超时后 Chrome 自动关闭", "en": "⚠ duration is REQUIRED (minutes); Chrome auto-closes when it expires"},
	"docs.dStop":       {"zh": "停止指定 profile 的 Chrome（移除路由，隧道不动）", "en": "Stop a profile's Chrome (route removed, tunnel untouched)"},
	"docs.tabsH":       {"zh": "标签页管理", "en": "Tab Management"},
	"docs.dListTabs":   {"zh": "列出指定 profile 的所有标签页", "en": "List all tabs of a profile"},
	"docs.dNewTab":     {"zh": "在指定 profile 中打开新标签页。必须声明存活时长，到期自动关闭标签回收内存", "en": "Open a new tab in a profile. A lifetime is REQUIRED; the tab auto-closes when it expires to reclaim memory"},
	"docs.dNewTabWarn": {"zh": "⚠ close_after 为必填参数（单位：秒，1..604800），到期后标签自动关闭；缺省会返回 400 错误。手机端人工打开的标签默认 1 小时自动关闭", "en": "⚠ close_after is REQUIRED (seconds, 1..604800); the tab auto-closes when it expires, missing values get HTTP 400. Tabs opened by a human on the phone default to 1 hour"},
	"docs.dCloseTab":   {"zh": "关闭指定标签页", "en": "Close a tab"},
	"docs.cdpH":        {"zh": "CDP 原生透传", "en": "CDP Native Passthrough"},
	"docs.dVersion":    {"zh": "获取 Chrome 版本信息", "en": "Get Chrome version info"},
	"docs.dList":       {"zh": "列出所有标签页（CDP 原生格式）", "en": "List all tabs (native CDP format)"},
	"docs.dNewCDP":     {"zh": "CDP 原生打开标签页（Chrome 154 需 PUT）。注意：走 CDP 透传打开的标签不受 close_after 管理，请自行负责关闭", "en": "Open a tab via native CDP (Chrome 154 needs PUT). Note: tabs opened through CDP passthrough are NOT managed by close_after — close them yourself"},
	"docs.dWS":         {"zh": "WebSocket 连接到 CDP DevTools 协议", "en": "WebSocket connection to the CDP DevTools protocol"},
	"docs.examplesH":   {"zh": "快速接入示例", "en": "Quick Start Example"},
	"docs.pyTitle":     {"zh": "Python 接入示例", "en": "Python example"},
	"docs.copied":      {"zh": "已复制", "en": "Copied"},

	// 隧道
	"tunnel.exit":     {"zh": "aitun 进程退出 (exit: %v)", "en": "aitun process exited (exit: %v)"},
	"tunnel.noClient": {"zh": "aitun 客户端不可用: %v", "en": "aitun client unavailable: %v"},
}

func t(key string) string {
	if m, ok := i18nMap[key]; ok {
		if v, ok := m[currentLang]; ok && v != "" {
			return v
		}
	}
	return key
}

// tf 翻译后 fmt.Sprintf（供带参数的键使用）
func tf(key string, args ...interface{}) string {
	return fmt.Sprintf(t(key), args...)
}

func setLang(lang Lang) {
	currentLang = lang
}

func toggleLang() {
	if currentLang == LangZH {
		setLang(LangEN)
	} else {
		setLang(LangZH)
	}
}
