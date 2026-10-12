package main

// i18n.go — 中英文双语支持

type Lang string

const (
	LangZH Lang = "zh"
	LangEN Lang = "en"
)

var currentLang = LangZH

var i18nMap = map[string]map[Lang]string{
	// 托盘菜单
	"tray.run":          {"zh": "运行", "en": "Run"},
	"tray.stop":         {"zh": "停止", "en": "Stop"},
	"tray.lang":         {"zh": "EN", "en": "中文"},
	"tray.autoStart":    {"zh": "开机启动", "en": "Start at Boot"},
	"tray.cancelAuto":   {"zh": "取消开机启动", "en": "Cancel Boot Start"},
	"tray.cdpAddr":      {"zh": "复制地址", "en": "Copy URL"},
	"tray.settings":     {"zh": "设置", "en": "Settings"},
	"tray.quit":         {"zh": "退出", "en": "Quit"},

	// 设置页面
	"settings.title":       {"zh": "CDP Client 设置", "en": "CDP Client Settings"},
	"settings.aitunKey":     {"zh": "Aitun Key (留空则用临时域名)", "en": "Aitun Key (empty = temp domain)"},
	"settings.save":         {"zh": "保存", "en": "Save"},
	"settings.domain":       {"zh": "当前域名", "en": "Current Domain"},
	"settings.noDomain":     {"zh": "未运行", "en": "Not Running"},
	"settings.cdpAddr":      {"zh": "CDP 地址", "en": "CDP Address"},
	"settings.remoteAddr":   {"zh": "远程地址", "en": "Remote Address"},
	"settings.token":        {"zh": "访问 Token", "en": "Access Token"},
	"settings.regenerate":   {"zh": "重新生成", "en": "Regenerate"},
	"settings.tokenHint":    {"zh": "访问远程地址时需要携带此 Token 鉴权", "en": "This token is required for remote access authentication"},
	"settings.status":       {"zh": "状态", "en": "Status"},
	"settings.running":      {"zh": "运行中", "en": "Running"},
	"settings.stopped":      {"zh": "已停止", "en": "Stopped"},
	"settings.saved":        {"zh": "已保存!", "en": "Saved!"},
	"settings.tokenRegen":   {"zh": "Token 已重新生成!", "en": "Token regenerated!"},
	"settings.keyHint":      {"zh": "在 aitun.cc 注册获取 Key（可固定子域名）", "en": "Get your key at aitun.cc (for fixed subdomain)"},

	// Profile 管理
	"settings.profiles":     {"zh": "浏览器 Profile", "en": "Browser Profiles"},
	"settings.profile":      {"zh": "Profile", "en": "Profile"},
	"settings.addProfile":   {"zh": "+ 新增 Profile", "en": "+ Add Profile"},
	"settings.profileName":  {"zh": "Profile 名称", "en": "Profile Name"},
	"settings.port":         {"zh": "端口", "en": "Port"},
	"settings.delete":       {"zh": "删除", "en": "Delete"},
	"settings.copy":         {"zh": "复制地址", "en": "Copy URL"},
	"settings.noProfiles":   {"zh": "暂无 Profile", "en": "No Profiles"},
	"settings.profileAdded": {"zh": "Profile 已添加", "en": "Profile added"},
	"settings.profileDeleted": {"zh": "Profile 已删除", "en": "Profile deleted"},
	"settings.copyAll":      {"zh": "复制全部地址", "en": "Copy All URLs"},

	// 提示
	"toast.copied":          {"zh": "已复制带Token的远程地址到剪切板", "en": "Remote address with token copied to clipboard"},
	"toast.copiedAll":       {"zh": "已复制所有远程地址到剪切板", "en": "All remote addresses copied to clipboard"},
	"toast.notRunning":      {"zh": "请先启动服务", "en": "Please start the service first"},
	"toast.chromeNotFound":  {"zh": "未找到 Chrome，请安装: https://www.google.com/chrome/", "en": "Chrome not found, install: https://www.google.com/chrome/"},
	"toast.started":         {"zh": "CDP 服务已启动", "en": "CDP service started"},
	"toast.stopped":         {"zh": "CDP 服务已停止", "en": "CDP service stopped"},
	"toast.startFailed":     {"zh": "启动失败: ", "en": "Start failed: "},
	"toast.autoStartOn":     {"zh": "已设置开机启动", "en": "Boot start enabled"},
	"toast.autoStartOff":    {"zh": "已取消开机启动", "en": "Boot start disabled"},

	// 托盘 tooltip
	"tray.tooltip":          {"zh": "CDP Client", "en": "CDP Client"},
	"tray.tooltipRunning":   {"zh": "CDP Client (运行中)", "en": "CDP Client (Running)"},
}

func t(key string) string {
	if m, ok := i18nMap[key]; ok {
		if v, ok := m[currentLang]; ok && v != "" {
			return v
		}
	}
	return key
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
