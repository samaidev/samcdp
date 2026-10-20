package main

// settings_page.go — 本地设置页面（仅本地 127.0.0.1 访问，管理配置）
// 远程 API 文档页面在 proxy_docs.go 中

const settingsHTML = `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>samcdp 设置</title>
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
.status-dot { width: 10px; height: 10px; border-radius: 50%; }
.status-dot.running { background: #4caf50; box-shadow: 0 0 8px #4caf50; }
.status-dot.stopped { background: #f44336; }
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
  box-shadow: 0 4px 16px rgba(0,0,0,.15);
}
#toast.show { opacity: 1; }
</style>
</head>
<body>
<h1>⚙️ samcdp 设置</h1>

<h2>远程地址</h2>
<div class="url-box">
  <span class="url empty" id="remoteUrl">获取中...</span>
  <button class="btn btn-secondary btn-sm" onclick="copyRemoteUrl()">复制</button>
</div>
<div class="hint" style="margin-top:6px">访问此地址查看 API 文档和接口说明（需带 ?token=xxx）</div>

<h2>aitun Key</h2>
<div class="field">
  <input type="password" id="aitunKey" value="" placeholder="留空则使用临时域名">
  <div class="hint">在 aitun.cc 注册获取，用于固定子域名</div>
</div>

<h2>运行状态</h2>
<div class="status-bar">
  <span class="status-dot stopped" id="statusDot"></span>
  <span id="statusText">已停止</span>
</div>

<h2>Profile 管理</h2>
<div id="profileList"></div>
<button class="btn btn-secondary btn-sm" onclick="addProfile()" style="margin-top:8px">+ 新增 Profile</button>

<h2>Token</h2>
<div class="field">
  <input type="text" id="tokenValue" value="" readonly>
  <div class="hint">远程 API 鉴权用，格式: ?token=xxx</div>
  <button class="btn btn-secondary" onclick="regenerateToken()" style="margin-top:8px">重新生成</button>
</div>

<button class="btn btn-primary" onclick="saveSettings()">保存设置</button>

<div id="toast"></div>

<script>
var baseUrl = '';
var copyableUrl = '';

function saveSettings() {
  var key = document.getElementById('aitunKey').value.trim();
  fetch('/api/settings', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({aitun_key: key})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast(d.msg || '已保存'); }
  });
}

function regenerateToken() {
  fetch('/api/regenerate-token', {method:'POST'}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) {
      document.getElementById('tokenValue').value = d.token;
      showToast('Token 已重新生成');
    }
  });
}

function addProfile() {
  var name = prompt('输入 Profile 名称（如 agent-3）:');
  if (!name || !name.trim()) return;
  name = name.trim();
  fetch('/api/profile/add', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast('已添加 ' + name); refreshStatus(); }
    else { showToast(d.error || '添加失败'); }
  });
}

function deleteProfile(name) {
  if (!confirm('删除 Profile "' + name + '"？')) return;
  fetch('/api/profile/delete', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast('已删除 ' + name); refreshStatus(); }
  });
}

function toggleProfile(name, enabled) {
  fetch('/api/profile/toggle', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({name: name, enabled: enabled})}).then(function(r){return r.json();}).then(function(d){
    if (d.ok) { showToast('已保存'); refreshStatus(); }
  });
}

function copyRemoteUrl() {
  if (!copyableUrl) { showToast('地址未就绪'); return; }
  var token = document.getElementById('tokenValue').value;
  var url = copyableUrl + (token && token != '-' ? '?token=' + token : '');
  navigator.clipboard.writeText(url).then(function(){ showToast('已复制: ' + url); });
}

function copyText(t) {
  navigator.clipboard.writeText(t).then(function(){ showToast('已复制'); });
}

function refreshStatus() {
  fetch('/api/status').then(function(r){return r.json();}).then(function(d){
    var dot = document.getElementById('statusDot');
    var text = document.getElementById('statusText');
    var tokenEl = document.getElementById('tokenValue');
    var urlEl = document.getElementById('remoteUrl');

    baseUrl = d.base_url || '';
    tokenEl.value = d.token || '-';

    if (d.running) {
      dot.className = 'status-dot running';
      text.textContent = '运行中 (' + d.profiles.length + ' profiles)';
    } else {
      dot.className = 'status-dot stopped';
      text.textContent = '已停止';
    }

    // 远程地址规则：隧道在线→临时代理/子域名地址；隧道连接中→提示（不显示局域网地址）；
    // 隧道未启用→本地地址（此时它是唯一可达入口）
    if (baseUrl) {
      copyableUrl = baseUrl;
      urlEl.className = 'url';
      urlEl.textContent = baseUrl;
    } else if (d.tunnel_running) {
      copyableUrl = '';
      urlEl.className = 'url empty';
      urlEl.textContent = '隧道连接中…（上线后显示临时代理/子域名地址）';
    } else {
      urlEl.className = 'url empty';
      urlEl.textContent = d.local_url || '未启动';
    }

    // Render profiles
    var list = document.getElementById('profileList');
    list.innerHTML = '';
    d.profiles.forEach(function(p) {
      var card = document.createElement('div');
      card.className = 'profile-card';
      var statusClass = p.running ? 'on' : 'off';
      var statusText = p.running ? '● 运行中' : '○ 已停止';
      card.innerHTML =
        '<div class="profile-info">' +
          '<div class="profile-name">' + p.name + '</div>' +
          '<div class="profile-port">Port: ' + p.port + (p.enabled ? '' : ' (已禁用)') + '</div>' +
          '<div class="profile-status ' + statusClass + '">' + statusText + '</div>' +
        '</div>' +
        '<div class="profile-actions">' +
          '<button class="btn btn-sm btn-danger" onclick="deleteProfile(\'' + p.name + '\')">删除</button>' +
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
  setTimeout(function(){ t.className = ''; }, 2000);
}

setInterval(refreshStatus, 3000);
refreshStatus();
</script>
</body>
</html>`

// renderSettingsHTML 渲染设置页面
func renderSettingsHTML() string {
	return settingsHTML
}
