package main

// proxy_docs.go — CDP 管理代理的 API 文档页面
// 访问根路径 / 时展示所有 API 接口、鉴权方式、示例
// v1.4.0: 页面按当前语言渲染（默认英文）

import (
	"net/http"
	"strings"
)

// handleDocsPage 返回 API 文档 HTML 页面
func (p *CDPProxy) handleDocsPage(w http.ResponseWriter, r *http.Request) {
	baseURL := tunnel.GetPublicURL()
	if baseURL == "" {
		baseURL = "http://127.0.0.1:9333"
	}
	token := cfg.GetAuthToken()

	// 检查 token 鉴权：如果带了 token 参数或 Bearer 头，展示完整文档
	// 如果没带 token，只展示基础信息
	authed := checkToken(r)

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if authed {
		w.Write([]byte(renderFullDocs(baseURL, token)))
	} else {
		w.Write([]byte(renderLockedDocs(baseURL)))
	}
}

func renderLockedDocs(baseURL string) string {
	return `<!DOCTYPE html><html lang="` + string(currentLang) + `"><head><meta charset="UTF-8">
<title>samcdp</title>
<style>
body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:40px;margin:0}
.box{max-width:700px;margin:0 auto;text-align:center}
h1{color:#ff8c00;font-size:28px}
.locked{background:#16213e;border:1px solid #e94560;border-radius:12px;padding:30px;margin:20px 0}
.hint{color:#a0a0a0;font-size:14px;margin-top:10px}
code{background:#0f3460;padding:2px 8px;border-radius:4px;color:#ff8c00}
</style></head><body>
<div class="box">
<h1>🔒 samcdp</h1>
<div class="locked">
<p style="font-size:18px">` + t("docs.needAuth") + `</p>
<p class="hint">` + t("docs.addToken") + ` <code>?token=YOUR_TOKEN</code></p>
<p class="hint">` + t("docs.orHeader") + ` <code>Authorization: Bearer YOUR_TOKEN</code></p>
</div>
</div></body></html>`
}

// pyExample 按当前语言生成 Python 快速接入示例（注释随语言切换）
func pyExample(baseURL, token string) string {
	c1 := "# List all profiles"
	c2 := "# Start agent-1's Chrome (auto-closes after 30 min)"
	c3 := "# Open Baidu (lifetime REQUIRED: 3600s = 1h, auto-closes on expiry)"
	c4 := "# List tabs"
	c5 := "# Stop manually when done"
	if currentLang == LangZH {
		c1 = "# 列出所有 profile"
		c2 = "# 启动 agent-1 的 Chrome（运行 30 分钟后自动关闭）"
		c3 = "# 打开百度（必须声明存活时长：3600 秒 = 1 小时，到期自动关闭）"
		c4 = "# 查看标签页"
		c5 = "# 用完手动停止"
	}
	var b strings.Builder
	b.WriteString("import requests&#10;BASE = \"")
	b.WriteString(baseURL)
	b.WriteString("\"&#10;TOKEN = \"")
	b.WriteString(token)
	b.WriteString("\"&#10;")
	b.WriteString(c1)
	b.WriteString("&#10;r = requests.get(f\"{BASE}/api/profiles?token={TOKEN}\")&#10;")
	b.WriteString(c2)
	b.WriteString("&#10;r = requests.post(f\"{BASE}/api/profiles/agent-1/start?token={TOKEN}&duration=30\")&#10;")
	b.WriteString(c3)
	b.WriteString("&#10;r = requests.post(f\"{BASE}/api/profiles/agent-1/tabs/new?token={TOKEN}&url=https://www.baidu.com&close_after=3600\")&#10;")
	b.WriteString(c4)
	b.WriteString("&#10;r = requests.get(f\"{BASE}/api/profiles/agent-1/tabs?token={TOKEN}\")&#10;")
	b.WriteString(c5)
	b.WriteString("&#10;r = requests.post(f\"{BASE}/api/profiles/agent-1/stop?token={TOKEN}\")")
	return b.String()
}

func renderFullDocs(baseURL, token string) string {
	var b strings.Builder
	b.WriteString(`<!DOCTYPE html><html lang="` + string(currentLang) + `"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>samcdp API</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,-apple-system,sans-serif;background:#0f0f23;color:#e0e0e0;padding:24px}
.container{max-width:900px;margin:0 auto}
h1{color:#ff8c00;font-size:24px;margin-bottom:8px}
.subtitle{color:#a0a0a0;font-size:14px;margin-bottom:24px}
.section{background:#16213e;border:1px solid #233;border-radius:10px;padding:20px;margin-bottom:16px}
.section h2{color:#ff8c00;font-size:16px;margin-bottom:12px;display:flex;align-items:center;gap:8px}
.section h2 .badge{background:#0f3460;color:#e94560;padding:2px 8px;border-radius:4px;font-size:11px}
.endpoint{display:flex;align-items:flex-start;gap:12px;padding:10px 0;border-bottom:1px solid #1a1a3e}
.endpoint:last-child{border-bottom:none}
.method{font-weight:700;font-size:12px;padding:3px 8px;border-radius:4px;min-width:60px;text-align:center;flex-shrink:0}
.method.GET{background:#1b5e20;color:#81c784}
.method.POST{background:#1565c0;color:#64b5f6}
.method.DELETE{background:#b71c1c;color:#ef9a9a}
.method.WS{background:#4a148c;color:#ce93d8}
.path{color:#e0e0e0;font-size:13px;font-family:'Cascadia Code',Consolas,monospace;word-break:break-all}
.desc{color:#a0a0a0;font-size:12px;margin-top:4px}
.example{background:#0d1117;border:1px solid #233;border-radius:6px;padding:10px;margin-top:6px;font-size:12px;color:#7ec699;font-family:monospace;word-break:break-all;cursor:pointer}
.example:hover{background:#161b22}
.example:active{background:#1c2330}
.token-box{background:#1a1a2e;border:1px solid #ff8c00;border-radius:8px;padding:14px;margin-bottom:16px;display:flex;align-items:center;justify-content:space-between;gap:12px}
.token-box .label{color:#ff8c00;font-weight:600;font-size:13px}
.token-box .value{color:#7ec699;font-family:monospace;font-size:14px;word-break:break-all;flex:1}
.copy-btn{background:#ff8c00;color:#fff;border:none;padding:6px 16px;border-radius:6px;cursor:pointer;font-size:12px;font-weight:600;flex-shrink:0}
.copy-btn:hover{background:#ff6347}
.toast{position:fixed;bottom:20px;right:20px;padding:12px 24px;background:#ff8c00;color:#fff;border-radius:8px;font-weight:600;opacity:0;transition:opacity .3s;pointer-events:none;z-index:100}
.toast.show{opacity:1}
.profile-status{font-size:13px;color:#81c784;margin-top:4px}
.profile-status.offline{color:#ef9a9a}
</style></head><body>
<div class="container">
<h1>🚀 samcdp API</h1>
<p class="subtitle">` + t("docs.subtitle") + `</p>

<div class="token-box">
<span class="label">Base URL</span>
<span class="value" id="baseUrl">` + baseURL + `</span>
<button class="copy-btn" onclick="copyText('` + baseURL + `')">` + t("sp.copy") + `</button>
</div>

<div class="token-box">
<span class="label">Token</span>
<span class="value" id="token">` + token + `</span>
<button class="copy-btn" onclick="copyText('` + token + `')">` + t("sp.copy") + `</button>
</div>

<div class="section">
<h2>` + t("docs.authH") + `</h2>
<div class="endpoint">
<span class="method GET">PARAM</span>
<div><div class="path">?token=` + token + `</div>
<div class="desc">` + t("docs.authParam") + `</div></div>
</div>
<div class="endpoint">
<span class="method GET">HEADER</span>
<div><div class="path">Authorization: Bearer ` + token + `</div>
<div class="desc">` + t("docs.authHeader") + `</div></div>
</div>
</div>

<div class="section">
<h2>` + t("docs.profilesH") + ` <span class="badge">/api/profiles</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/api/profiles?token=TOKEN</div>
<div class="desc">` + t("docs.dListProf") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -s "` + baseURL + `/api/profiles?token=` + token + `" | python -m json.tool</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles?token=TOKEN</div>
<div class="desc">` + t("docs.dCreateProf") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles?token=` + token + `" -H "Content-Type: application/json" -d '{"name":"agent-3"}'</div>
</div>
</div>

<div class="endpoint">
<span class="method DELETE">DEL</span>
<div>
<div class="path">/api/profiles/NAME?token=TOKEN</div>
<div class="desc">` + t("docs.dDelProf") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X DELETE "` + baseURL + `/api/profiles/agent-3?token=` + token + `"</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/start?token=TOKEN&duration=MINUTES</div>
<div class="desc">` + t("docs.dStart") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-3/start?token=` + token + `&duration=30"</div>
<div class="desc" style="color:#ff8c00">` + t("docs.dStartWarn") + `</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/stop?token=TOKEN</div>
<div class="desc">` + t("docs.dStop") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-3/stop?token=` + token + `"</div>
</div>
</div>
</div>

<div class="section">
<h2>` + t("docs.tabsH") + ` <span class="badge">/api/profiles/NAME/tabs</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/api/profiles/NAME/tabs?token=TOKEN</div>
<div class="desc">` + t("docs.dListTabs") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -s "` + baseURL + `/api/profiles/agent-1/tabs?token=` + token + `" | python -m json.tool</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/tabs/new?url=URL&close_after=SECONDS&token=TOKEN</div>
<div class="desc">` + t("docs.dNewTab") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-1/tabs/new?url=https://www.baidu.com&close_after=3600&token=` + token + `"</div>
<div class="desc" style="color:#ff8c00">` + t("docs.dNewTabWarn") + `</div>
</div>
</div>

<div class="endpoint">
<span class="method DELETE">DEL</span>
<div>
<div class="path">/api/profiles/NAME/tabs/TAB_ID?token=TOKEN</div>
<div class="desc">` + t("docs.dCloseTab") + `</div>
<div class="example" onclick="copyText(this.textContent)">curl -X DELETE "` + baseURL + `/api/profiles/agent-1/tabs/A1B2C3D4?token=` + token + `"</div>
</div>
</div>
</div>

<div class="section">
<h2>` + t("docs.cdpH") + ` <span class="badge">/&lt;profile&gt;/json/*</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/version?token=TOKEN</div>
<div class="desc">` + t("docs.dVersion") + `</div>
</div>
</div>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/list?token=TOKEN</div>
<div class="desc">` + t("docs.dList") + `</div>
</div>
</div>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/new/URL?token=TOKEN</div>
<div class="desc">` + t("docs.dNewCDP") + `</div>
</div>
</div>

<div class="endpoint">
<span class="method WS">WS</span>
<div>
<div class="path">/agent-1/devtools/page/TAB_ID?token=TOKEN</div>
<div class="desc">` + t("docs.dWS") + `</div>
</div>
</div>
</div>

<div class="section">
<h2>` + t("docs.examplesH") + `</h2>
<div class="endpoint">
<span class="method POST">PY</span>
<div>
<div class="path">` + t("docs.pyTitle") + `</div>
<div class="example" onclick="copyText(this.textContent)">` + pyExample(baseURL, token) + `</div>
</div>
</div>
</div>

</div>
<div class="toast" id="toast"></div>
<script>
function copyText(t){navigator.clipboard.writeText(t.trim()).then(()=>{var e=document.getElementById('toast');e.textContent='` + t("docs.copied") + `';e.className='show';setTimeout(()=>e.className='',1500)})}
</script>
</body></html>`)
	return b.String()
}
