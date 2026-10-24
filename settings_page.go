package main

// settings_page.go — 本地设置页面（仅本地 127.0.0.1 访问，管理配置）
// 布局参考 Android APK 设置页：远程地址(隧道)卡片在上、局域网地址在下方、
// 隧道状态与错误实时显示、Key 决定模式（填写=子域名，留空=临时代理）
// 远程 API 文档页面在 proxy_docs.go 中
// v1.4.0: 页面按当前语言渲染（默认英文），静态文本走 %__MARK__% 替换，
// JS 动态文本走服务端注入的 I18N 字典 + T(key)

import (
	"encoding/json"
	"strings"
)

const settingsTemplate = `<!DOCTYPE html>
<html lang="%__HTML_LANG__%">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>%__TITLE__%</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: linear-gradient(135deg, #fff5e6 0%, #ffe8cc 100%);
  color: #5c4033; font-size: 14px; padding: 24px; min-height: 100vh;
}
h1 { font-size: 22px; margin-bottom: 20px; color: #d2691e; }
h2 { font-size: 15px; margin: 20px 0 10px; color: #8b6914; }
.field { margin-bottom: 16px; }
.field label { display: block; margin-bottom: 6px; font-weight: 600; color: #8b6914; font-size: 13px; }
.field input[type="text"], .field input[type="password"] {
  width: 100%; padding: 10px 12px; border: 1px solid #deb887; border-radius: 8px;
  background: #fffaf0; color: #5c4033; font-size: 14px; outline: none; transition: border-color .2s;
}
.field input:focus { border-color: #ff8c00; box-shadow: 0 0 0 3px rgba(255,140,0,.15); }
.field .hint { font-size: 12px; color: #a0826d; margin-top: 4px; }
.url-box {
  padding: 12px; border: 1px solid #deb887; border-radius: 8px;
  background: #fffaf0; display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.url-box .url { color: #2e7d32; font-size: 14px; word-break: break-all; font-family: monospace; }
.url-box .url.empty { color: #a0826d; }
.url-box .url.connecting { color: #d2610f; }
.url-box.muted { background: #fffdf8; border-style: dashed; }
.url-box.muted .url { color: #8b6914; font-size: 13px; }
.url-box.muted .url.empty { color: #c9b8a8; }
.error-line {
  display: none; margin-top: 8px; padding: 8px 12px; border-radius: 8px;
  background: #fdecea; border: 1px solid #ef9a9a; color: #c62828; font-size: 12px; word-break: break-all;
}
.btn {
  padding: 8px 20px; border: none; border-radius: 8px; cursor: pointer;
  font-size: 13px; font-weight: 600; transition: all .15s;
}
.btn-primary { background: linear-gradient(135deg, #ff8c00, #ff6347); color: #fff; }
.btn-primary:hover { box-shadow: 0 4px 12px rgba(255,140,0,.4); transform: translateY(-1px); }
.btn-secondary { background: #fffaf0; color: #ff8c00; border: 1px solid #deb887; }
.btn-secondary:hover { background: #ff8c00; color: #fff; }
.btn-sm { padding: 5px 14px; font-size: 12px; }
.btn-danger { background: #fffaf0; color: #c62828; border: 1px solid #ef9a9a; }
.btn-danger:hover { background: #c62828; color: #fff; }
.status-bar {
  margin-top: 4px; padding: 12px; border-radius: 8px; background: #fffaf0;
  border: 1px solid #deb887; display: flex; align-items: center; gap: 8px;
}
.status-bar + .status-bar { margin-top: 8px; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.status-dot.running { background: #4caf50; box-shadow: 0 0 8px #4caf50; }
.status-dot.connecting { background: #ff8c00; animation: pulse 1.2s ease-in-out infinite; }
.status-dot.off { background: #c9b8a8; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .35; } }
.mode-line { margin-top: 8px; font-size: 12px; color: #8b6914; }
.mode-line b { color: #d2610f; }
.profile-card {
  padding: 14px; margin-bottom: 10px; border-radius: 10px; background: #fffaf0;
  border: 1px solid #deb887; display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.profile-info { flex: 1; }
.profile-name { font-weight: 700; color: #d2691e; font-size: 15px; }
.profile-port { font-size: 12px; color: #a0826d; margin-top: 2px; }
.profile-status { font-size: 12px; margin-top: 4px; }
.profile-status.on { color: #2e7d32; }
.profile-status.off { color: #a0826d; }
.profile-actions { display: flex; gap: 6px; flex-shrink: 0; }
.profile-toggle { position: relative; width: 40px; height: 22px; }
.profile-toggle input { opacity: 0; width: 0; height: 0; }
.profile-toggle .slider {
  position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0;
  background: #ddd; border-radius: 22px; transition: .3s;
}
.profile-toggle .slider:before {
  position: absolute; content: ""; height: 16px; width: 16px; left: 3px; bottom: 3px;
  background: #fff; border-radius: 50%; transition: .3s;
}
.profile-toggle input:checked + .slider { background: #ff8c00; }
.profile-toggle input:checked + .slider:before { transform: translateX(18px); }
#toast {
  position: fixed; bottom: 20px; right: 20px; padding: 12px 24px;
  background: linear-gradient(135deg, #ff8c00, #ff6347); color: #fff; border-radius: 8px; font-weight: 600;
  opacity: 0; transition: opacity .3s; pointer-events: none; z-index: 100;
  box-shadow: 0 4px 16px rgba(0,0,0,.15); max-width: 80vw; word-break: break-all;
}
#toast.show { opacity: 1; }
.lang-select {
  padding: 8px 12px; border: 1px solid #deb887; border-radius: 8px;
  background: #fffaf0; color: #5c4033; font-size: 13px; outline: none; cursor: pointer;
}
</style>
</head>
<body>
<h1>⚙️ %__TITLE__%</h1>

<h2>%__REMOTE_H__%</h2>
<div class="url-box">
  <span class="url empty" id="remoteUrl">%__FETCHING__%</span>
  <button class="btn btn-secondary btn-sm" onclick="copyRemoteUrl()">%__COPY__%</button>
</div>
<div class="error-line" id="tunnelError"></div>

<h2 style="margin-top:16px">%__LAN_H__%</h2>
<div class="url-box muted">
  <span class="url empty" id="lanUrl">%__FETCHING__%</span>
  <button class="btn btn-secondary btn-sm" onclick="copyLanUrl()">%__COPY__%</button>
</div>
<div class="hint" style="margin-top:6px">%__LAN_HINT__%</div>

<h2>%__TUNNEL_H__%</h2>
<div class="field">
  <label>aitun Key</label>
  <input type="password" id="aitunKey" value="" placeholder="%__KEY_PLACEHOLDER__%">
  <div class="hint">%__KEY_HINT__%</div>
</div>
<div class="mode-line">%__CUR_MODE__%<b id="tunnelMode">—</b></div>

<h2>%__STATUS_H__%</h2>
<div class="status-bar">
  <span class="status-dot off" id="statusDot"></span>
  <span id="statusText">%__TUNNEL_OFF__%</span>
</div>
<div class="status-bar">
  <span class="status-dot off" id="tunnelDot"></span>
  <span id="tunnelText">%__TUNNEL_OFF__%</span>
</div>

<h2>%__PROFILES_H__%</h2>
<div id="profileList"></div>
<button class="btn btn-secondary btn-sm" onclick="addProfile()" style="margin-top:8px">%__ADD_PROFILE__%</button>

<h2>%__TOKEN_H__%</h2>
<div class="field">
  <input type="text" id="tokenValue" value="" readonly>
  <div class="hint">%__TOKEN_HINT__%</div>
  <button class="btn btn-secondary" onclick="regenerateToken()" style="margin-top:8px">%__REGEN__%</button>
</div>

<div class="field" style="margin-top:14px">
  <label>%__LANG_LABEL__%</label>
  <select id="langSel" class="lang-select" onchange="changeLang(this.value)">
    <option value="en">English</option>
    <option value="zh">中文</option>
  </select>
</div>

<button class="btn btn-primary" onclick="saveSettings()">%__SAVE_BTN__%</button>

<div id="toast"></div>

<script>
var I18N = %__I18N_JSON__%;
function T(k){ return I18N[k] || k; }
// preselect the language the page was rendered with
document.getElementById('langSel').value = '%__HTML_LANG__%';

function changeLang(lang) {
  fetch('/api/lang', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({lang: lang})}).then(function(){ location.reload(); });
}

function saveSettings() {
  var key = document.getElementById('aitunKey').value.trim();
  fetch('/api/settings', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({aitun_key: key})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(d.msg || T('saved')); refreshStatus(); }
    else { showToast(d.error || T('saveFail')); }
  });
}

function regenerateToken() {
  if (!confirm(T('regenConfirm'))) return;
  fetch('/api/regenerate-token', {method:'POST'}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(T('regenDone')); refreshStatus(); }
  });
}

function addProfile() {
  var name = prompt(T('namePrompt'));
  if (!name || !name.trim()) return;
  name = name.trim();
  fetch('/api/profile/add', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(T('added') + name); refreshStatus(); }
    else { showToast(d.error || T('addFail')); }
  });
}

function deleteProfile(name) {
  if (!confirm(T('delConfirm') + name + T('delConfirmEnd'))) return;
  fetch('/api/profile/delete', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(T('deleted') + name); refreshStatus(); }
  });
}

function toggleProfile(name, enabled) {
  fetch('/api/profile/toggle', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name, enabled: enabled})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(T('saved')); refreshStatus(); }
  });
}

function copyRemoteUrl() {
  if (!copyableUrl) { showToast(T('urlNotReady')); return; }
  var token = document.getElementById('tokenValue').value;
  var url = copyableUrl + (token && token != '-' ? '?token=' + token : '');
  navigator.clipboard.writeText(url).then(function(){ showToast(T('copied') + url); });
}

function copyLanUrl() {
  var lan = document.getElementById('lanUrl').textContent;
  if (!lan || lan == T('fetching') || lan == T('notStarted')) { showToast(T('urlNotReady')); return; }
  var token = document.getElementById('tokenValue').value;
  var url = lan + (token && token != '-' ? '?token=' + token : '');
  navigator.clipboard.writeText(url).then(function(){ showToast(T('copied') + url); });
}

function copyText(t) {
  navigator.clipboard.writeText(t).then(function(){ showToast(T('copiedShort')); });
}

function refreshStatus() {
  fetch('/api/status').then(function(r){return r.json();}).then(function(d){
    var dot = document.getElementById('statusDot');
    var text = document.getElementById('statusText');
    var tokenEl = document.getElementById('tokenValue');
    var urlEl = document.getElementById('remoteUrl');
    var lanEl = document.getElementById('lanUrl');
    var keyEl = document.getElementById('aitunKey');

    baseUrl = d.base_url || '';
    var lan = d.lan_url || d.local_url || '';
    // 复制目标：隧道在线→隧道地址；隧道连接中→不可复制；未启用→局域网地址
    copyableUrl = baseUrl || (d.tunnel_running ? '' : lan);
    if (!document.activeElement || document.activeElement !== keyEl) {
      keyEl.value = d.aitun_key || '';
    }
    tokenEl.value = d.token || '-';

    if (d.running) {
      dot.className = 'status-dot running';
      text.textContent = T('runningN') + d.profiles.length + T('profilesN');
    } else {
      dot.className = 'status-dot off';
      text.textContent = T('stopped');
    }

    // 远程地址规则（与 APK 一致）：隧道在线→隧道地址；连接中→占位提示；未启用→提示
    if (baseUrl) {
      urlEl.className = 'url';
      urlEl.textContent = baseUrl;
    } else if (d.tunnel_running) {
      urlEl.className = 'url empty connecting';
      urlEl.textContent = T('connecting');
    } else {
      urlEl.className = 'url empty';
      urlEl.textContent = d.running ? T('tunnelDisabled') : T('svcStopped');
    }

    // 局域网地址：始终显示（隧道不可用时的备用入口）
    if (lan) {
      lanEl.className = 'url';
      lanEl.textContent = lan;
    } else {
      lanEl.className = 'url empty';
      lanEl.textContent = T('notStarted');
    }

    // 隧道错误行（实时，来自 aitun 输出）
    var errEl = document.getElementById('tunnelError');
    if (d.tunnel_error) {
      errEl.style.display = 'block';
      errEl.textContent = '⚠ ' + d.tunnel_error;
    } else {
      errEl.style.display = 'none';
    }

    // 隧道状态行
    var tDot = document.getElementById('tunnelDot');
    var tText = document.getElementById('tunnelText');
    if (baseUrl) {
      tDot.className = 'status-dot running';
      tText.textContent = T('tunnelOnline');
    } else if (d.tunnel_running) {
      tDot.className = 'status-dot connecting';
      tText.textContent = T('tunnelConn');
    } else {
      tDot.className = 'status-dot off';
      tText.textContent = T('tunnelDisabled');
    }

    // 当前模式（由 Key 是否填写决定，与 APK 一致）
    var modeEl = document.getElementById('tunnelMode');
    if (d.aitun_key) {
      modeEl.textContent = T('modeSub');
    } else {
      modeEl.textContent = T('modeTemp');
    }

    // Render profiles
    var list = document.getElementById('profileList');
    list.innerHTML = '';
    d.profiles.forEach(function(p) {
      var card = document.createElement('div');
      card.className = 'profile-card';
      var statusClass = p.running ? 'on' : 'off';
      var statusText = p.running ? T('profRunning') : T('profStopped');
      card.innerHTML =
        '<div class="profile-info">' +
          '<div class="profile-name">' + p.name + '</div>' +
          '<div class="profile-port">Port: ' + p.port + (p.enabled ? '' : ' ' + T('profDisabled')) + '</div>' +
          '<div class="profile-status ' + statusClass + '">' + statusText + '</div>' +
        '</div>' +
        '<div class="profile-actions">' +
          '<button class="btn btn-sm btn-danger" onclick="deleteProfile(\'' + p.name + '\')">' + T('delete') + '</button>' +
          '<label class="profile-toggle">' +
            '<input type="checkbox" ' + (p.enabled ? 'checked' : '') + ' onchange="toggleProfile(\'' + p.name + '\', this.checked)">' +
            '<span class="slider"></span>' +
          '</label>' +
        '</div>';
      list.appendChild(card);
    });
  });
}

function showToast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.className = 'show';
  setTimeout(function(){ t.className = ''; }, 2500);
}

setInterval(refreshStatus, 3000);
refreshStatus();
</script>
</body>
</html>`

// settingsJSDict — 注入到页面的 JS 动态文本字典
func settingsJSDict() map[string]string {
	return map[string]string{
		"saved":          t("sp.jsSaved"),
		"saveFail":       t("sp.jsSaveFail"),
		"regenConfirm":   t("sp.jsRegenConfirm"),
		"regenDone":      t("sp.jsRegenDone"),
		"namePrompt":     t("sp.jsNamePrompt"),
		"added":          t("sp.jsAdded"),
		"addFail":        t("sp.jsAddFail"),
		"delConfirm":     t("sp.jsDelConfirm"),
		"delConfirmEnd":  t("sp.jsDelConfirmEnd"),
		"deleted":        t("sp.jsDeleted"),
		"urlNotReady":    t("sp.jsUrlNotReady"),
		"copied":         t("sp.jsCopied"),
		"copiedShort":    t("sp.jsCopiedShort"),
		"runningN":       t("sp.jsRunningN"),
		"profilesN":      t("sp.jsProfilesN"),
		"stopped":        t("sp.jsStopped"),
		"connecting":     t("sp.jsConnecting"),
		"tunnelDisabled": t("sp.tunnelOff"),
		"svcStopped":     t("sp.jsSvcStopped"),
		"tunnelOnline":   t("sp.jsTunnelOnline"),
		"tunnelConn":     t("sp.jsTunnelConn"),
		"modeSub":        t("sp.jsModeSub"),
		"modeTemp":       t("sp.jsModeTemp"),
		"profRunning":    t("sp.jsProfRunning"),
		"profStopped":    t("sp.jsProfStopped"),
		"profDisabled":   t("sp.jsProfDisabled"),
		"delete":         t("sp.jsDelete"),
		"fetching":       t("sp.fetching"),
		"notStarted":     t("sp.notStarted"),
	}
}

// renderSettingsHTML 按当前语言渲染设置页面
func renderSettingsHTML() string {
	dictJSON, err := json.Marshal(settingsJSDict())
	if err != nil {
		dictJSON = []byte("{}")
	}
	repl := strings.NewReplacer(
		"%__HTML_LANG__%", string(currentLang),
		"%__TITLE__%", t("settings.title"),
		"%__REMOTE_H__%", t("sp.remoteH"),
		"%__LAN_H__%", t("sp.lanH"),
		"%__LAN_HINT__%", t("sp.lanHint"),
		"%__TUNNEL_H__%", t("sp.tunnelH"),
		"%__KEY_PLACEHOLDER__%", t("sp.keyPlaceholder"),
		"%__KEY_HINT__%", t("sp.keyHint"),
		"%__CUR_MODE__%", t("sp.curMode"),
		"%__STATUS_H__%", t("sp.statusH"),
		"%__TUNNEL_OFF__%", t("sp.tunnelOff"),
		"%__PROFILES_H__%", t("sp.profilesH"),
		"%__ADD_PROFILE__%", t("settings.addProfile"),
		"%__TOKEN_H__%", t("sp.tokenH"),
		"%__TOKEN_HINT__%", t("sp.tokenHint"),
		"%__REGEN__%", t("settings.regenerate"),
		"%__LANG_LABEL__%", t("sp.langLabel"),
		"%__COPY__%", t("sp.copy"),
		"%__FETCHING__%", t("sp.fetching"),
		"%__SAVE_BTN__%", t("sp.saveBtn"),
		"%__I18N_JSON__%", string(dictJSON),
	)
	return repl.Replace(settingsTemplate)
}
