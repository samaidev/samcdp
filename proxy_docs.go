package main

// proxy_docs.go — CDP 管理代理的 API 文档页面
// 访问根路径 / 时展示所有 API 接口、鉴权方式、示例

import (
	"net/http"
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
	return `<!DOCTYPE html><html><head><meta charset="UTF-8">
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
<p style="font-size:18px">需要鉴权才能访问</p>
<p class="hint">在 URL 后添加 <code>?token=YOUR_TOKEN</code></p>
<p class="hint">或设置请求头 <code>Authorization: Bearer YOUR_TOKEN</code></p>
</div>
</div></body></html>`
}

func renderFullDocs(baseURL, token string) string {
	return `<!DOCTYPE html><html><head><meta charset="UTF-8">
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
<p class="subtitle">远程 Chrome CDP 管理接口 · 单隧道多 Profile 架构</p>

<div class="token-box">
<span class="label">Base URL</span>
<span class="value" id="baseUrl">` + baseURL + `</span>
<button class="copy-btn" onclick="copyText('` + baseURL + `')">复制</button>
</div>

<div class="token-box">
<span class="label">Token</span>
<span class="value" id="token">` + token + `</span>
<button class="copy-btn" onclick="copyText('` + token + `')">复制</button>
</div>

<div class="section">
<h2>鉴权方式</h2>
<div class="endpoint">
<span class="method GET">PARAM</span>
<div><div class="path">?token=` + token + `</div>
<div class="desc">在 URL 查询参数中传递 token</div></div>
</div>
<div class="endpoint">
<span class="method GET">HEADER</span>
<div><div class="path">Authorization: Bearer ` + token + `</div>
<div class="desc">在请求头中传递 token</div></div>
</div>
</div>

<div class="section">
<h2>Profile 管理 <span class="badge">/api/profiles</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/api/profiles?token=TOKEN</div>
<div class="desc">列出所有 profile 及运行状态、标签页数量</div>
<div class="example" onclick="copyText(this.textContent)">curl -s "` + baseURL + `/api/profiles?token=` + token + `" | python -m json.tool</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles?token=TOKEN</div>
<div class="desc">创建新 profile（自动分配端口）</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles?token=` + token + `" -H "Content-Type: application/json" -d '{"name":"agent-3"}'</div>
</div>
</div>

<div class="endpoint">
<span class="method DELETE">DEL</span>
<div>
<div class="path">/api/profiles/NAME?token=TOKEN</div>
<div class="desc">删除指定 profile（停止 Chrome + 移除配置）</div>
<div class="example" onclick="copyText(this.textContent)">curl -X DELETE "` + baseURL + `/api/profiles/agent-3?token=` + token + `"</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/start?token=TOKEN&duration=MINUTES</div>
<div class="desc">启动指定 profile 的 Chrome。必须指定运行时长，到期后自动关闭 Chrome 防止窗口堆积</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-3/start?token=` + token + `&duration=30"</div>
<div class="desc" style="color:#ff8c00">⚠ duration 为必填参数（单位：分钟），超时后 Chrome 自动关闭</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/stop?token=TOKEN</div>
<div class="desc">停止指定 profile 的 Chrome（移除路由，隧道不动）</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-3/stop?token=` + token + `"</div>
</div>
</div>
</div>

<div class="section">
<h2>标签页管理 <span class="badge">/api/profiles/NAME/tabs</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/api/profiles/NAME/tabs?token=TOKEN</div>
<div class="desc">列出指定 profile 的所有标签页</div>
<div class="example" onclick="copyText(this.textContent)">curl -s "` + baseURL + `/api/profiles/agent-1/tabs?token=` + token + `" | python -m json.tool</div>
</div>
</div>

<div class="endpoint">
<span class="method POST">POST</span>
<div>
<div class="path">/api/profiles/NAME/tabs/new?url=URL&close_after=SECONDS&token=TOKEN</div>
<div class="desc">在指定 profile 中打开新标签页。必须声明存活时长，到期自动关闭标签回收内存</div>
<div class="example" onclick="copyText(this.textContent)">curl -X POST "` + baseURL + `/api/profiles/agent-1/tabs/new?url=https://www.baidu.com&close_after=3600&token=` + token + `"</div>
<div class="desc" style="color:#ff8c00">⚠ close_after 为必填参数（单位：秒，1..604800），到期后标签自动关闭；缺省会返回 400 错误。手机端人工打开的标签默认 1 小时自动关闭</div>
</div>
</div>

<div class="endpoint">
<span class="method DELETE">DEL</span>
<div>
<div class="path">/api/profiles/NAME/tabs/TAB_ID?token=TOKEN</div>
<div class="desc">关闭指定标签页</div>
<div class="example" onclick="copyText(this.textContent)">curl -X DELETE "` + baseURL + `/api/profiles/agent-1/tabs/A1B2C3D4?token=` + token + `"</div>
</div>
</div>
</div>

<div class="section">
<h2>CDP 原生透传 <span class="badge">/&lt;profile&gt;/json/*</span></h2>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/version?token=TOKEN</div>
<div class="desc">获取 Chrome 版本信息</div>
</div>
</div>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/list?token=TOKEN</div>
<div class="desc">列出所有标签页（CDP 原生格式）</div>
</div>
</div>

<div class="endpoint">
<span class="method GET">GET</span>
<div>
<div class="path">/agent-1/json/new/URL?token=TOKEN</div>
<div class="desc">CDP 原生打开标签页（Chrome 154 需 PUT）。注意：走 CDP 透传打开的标签不受 close_after 管理，请自行负责关闭</div>
</div>
</div>

<div class="endpoint">
<span class="method WS">WS</span>
<div>
<div class="path">/agent-1/devtools/page/TAB_ID?token=TOKEN</div>
<div class="desc">WebSocket 连接到 CDP DevTools 协议</div>
</div>
</div>
</div>

<div class="section">
<h2>快速接入示例</h2>
<div class="endpoint">
<span class="method POST">PY</span>
<div>
<div class="path">Python 接入示例</div>
<div class="example" onclick="copyText(this.textContent)">import requests&#10;BASE = "` + baseURL + `"&#10;TOKEN = "` + token + `"&#10;# 列出所有 profile&#10;r = requests.get(f"{BASE}/api/profiles?token={TOKEN}")&#10;# 启动 agent-1 的 Chrome（运行 30 分钟后自动关闭）&#10;r = requests.post(f"{BASE}/api/profiles/agent-1/start?token={TOKEN}&duration=30")&#10;# 打开百度（必须声明存活时长：3600 秒 = 1 小时，到期自动关闭）&#10;r = requests.post(f"{BASE}/api/profiles/agent-1/tabs/new?token={TOKEN}&url=https://www.baidu.com&close_after=3600")&#10;# 查看标签页&#10;r = requests.get(f"{BASE}/api/profiles/agent-1/tabs?token={TOKEN}")&#10;# 用完手动停止&#10;r = requests.post(f"{BASE}/api/profiles/agent-1/stop?token={TOKEN}")</div>
</div>
</div>
</div>

</div>
<div class="toast" id="toast"></div>
<script>
function copyText(t){navigator.clipboard.writeText(t.trim()).then(()=>{var e=document.getElementById('toast');e.textContent='已复制';e.className='show';setTimeout(()=>e.className='',1500)})}
</script>
</body></html>`
}
