package cc.samaidev.samcdp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * L — tiny i18n helper (v1.4.0).
 *
 * English is the DEFAULT language; Chinese is opt-in via the settings screen
 * (persisted in the shared "cdpclient" prefs as "lang": "en"|"zh").
 *
 * Every activity/process calls {@link #load} before building UI (main process:
 * MainActivity/CdpService/Api docs page; profile processes :cdp1..6: TabActivity),
 * so the pref file is the single source of truth across processes — same
 * pattern the app already relies on for auth_token/port.
 */
public final class L {

    private static volatile String lang = "en"; // "en" (default) | "zh"

    private static final Map<String, String[]> D = new HashMap<>();

    private static void put(String key, String en, String zh) {
        D.put(key, new String[]{en, zh});
    }

    static {
        // ---- MainActivity: toasts ----
        put("main.copyConnecting", "Tunnel connecting — remote address will be copyable once online",
                "隧道连接中，上线后即可复制远程地址");
        put("main.copied", "Copied: %s", "已复制 %s");
        put("main.maxProfiles", "At most %d Profiles (isolated processes)", "最多 %d 个 Profile（独立进程隔离）");
        put("main.regenDone", "Regenerated and applied immediately", "已重新生成并立即生效");
        put("main.subdomainNeedKey", "Subdomain mode requires an aitun Key", "子域名模式需要填入 aitun Key");
        put("main.batteryIgnored", "Battery optimization ignored — revoke anytime in system settings",
                "已忽略电池优化，可在系统设置中撤回");
        put("main.batteryFail", "Cannot open battery optimization settings: %s", "无法打开电池优化设置: %s");
        put("main.saved", "Settings saved", "设置已保存");
        put("main.savedToken", "Settings saved — token applied immediately", "设置已保存，token 已立即生效");
        put("main.tunnelStoppedNeedKey", "Tunnel stopped: subdomain mode requires an aitun Key",
                "隧道已停止：子域名模式需要填入 aitun Key");
        put("main.notRunning", "Profile not running — flip its switch first", "profile 未运行，先打开开关启动");
        put("main.overlayPerm", "Showing the WebView needs the \"Display over other apps\" permission — please allow it on the next screen",
                "弹出 WebView 需要「显示在应用上层」权限，请在接下来的页面允许");
        put("main.overlayRetry", "Grant the overlay permission in system settings and retry", "请在系统设置中授予悬浮窗权限后重试");
        put("main.showFailed", "Show failed: %s", "显示失败: %s");
        put("main.showNoPopup", "WebView requested but not shown: in System Settings → App Management → samcdp allow \"Start in background\" and \"Display pop-up windows while running in background\"",
                "WebView 请求已发出但未显示：请在 系统设置→应用管理→samcdp 允许「后台弹出界面」和「自启动」");
        put("main.shown", "WebView is now visible", "WebView 已显示");

        // ---- MainActivity: rendered state ----
        put("main.tunnelConnecting", "Tunnel connecting… (temp proxy / subdomain address appears once online)",
                "隧道连接中…（上线后显示临时代理/子域名地址）");
        put("main.statusRunning", "Running (%d profiles)", "运行中（%d profiles）");
        put("main.statusStopped", "Stopped", "已停止");
        put("main.modeSub", "Subdomain", "子域名");
        put("main.modeTemp", "Temp proxy", "临时代理");
        put("main.tunnelOnline", "● Tunnel online: %s (%s)", "● 隧道在线: %s（%s）");
        put("main.tunnelModeMismatch", "⚠ UI options differ from the running tunnel (running: %s). Stop and restart the tunnel for changes to take effect",
                "⚠ 界面选项与运行中隧道不一致（运行中：%s）。改动需停止隧道后重新启动才会生效");
        put("main.stopTunnel", "Stop Tunnel", "停止隧道");
        put("main.startTunnel", "Start Tunnel", "启动隧道");
        put("main.connecting", "● Tunnel connecting…", "● 隧道连接中…");
        put("main.lastErr", "(last error: %s)", "（上次错误: %s）");
        put("main.tunnelError", "● Tunnel error: %s", "● 隧道错误: %s");
        put("main.tunnelOff", "Tunnel not started (LAN: %s)", "隧道未启动（局域网: %s）");
        put("main.runnerOn", "● Running · runner #%d%s", "● 运行中 · runner #%d%s");
        put("main.idle", "○ Idle", "○ 空闲");
        put("main.disabled", "○ Disabled", "○ 已禁用");
        put("main.showWebView", "Show WebView", "显示WebView");
        put("main.delete", "Delete", "删除");
        put("main.mins", " · %dm", " · %dm");

        // ---- MainActivity: static texts from layout XML ----
        put("main.title", "⚙ samcdp Settings", "⚙ samcdp 设置");
        put("main.remoteAddr", "Remote Address", "远程地址");
        put("main.copy", "Copy", "复制");
        put("main.apiHint", "Open this address in a browser to read the API docs (?token=xxx required)",
                "访问此地址查看 API 文档和接口说明（需带 ?token=xxx）");
        put("main.aitunTunnel", "aitun Tunnel", "aitun 隧道");
        put("main.keyFieldHint", "ait_xxxxxxxxxxxx (empty = temp proxy)", "ait_xxxxxxxxxxxx（留空则使用临时代理）");
        put("main.keyHint2", "Register at aitun.cc for a Key to get a fixed subdomain", "在 aitun.cc 注册获取 Key，用于固定子域名");
        put("main.modeTempRadio", "Temp proxy (auto-assigned, 24h)", "临时代理（自动分配，24h）");
        put("main.modeSubRadio", "Subdomain (Key required)", "子域名（需 Key）");
        put("main.battery", "Battery", "电池优化");
        put("main.tunnelOffStatic", "Tunnel not started", "隧道未启动");
        put("main.runStatus", "Status", "运行状态");
        put("main.stoppedStatic", "Stopped", "已停止");
        put("main.profiles", "Profiles", "Profile 管理");
        put("main.addProfile", "+ Add Profile", "+ 新增 Profile");
        put("main.tokenHint", "Token for the remote API, format: ?token=xxx", "远程 API 鉴权用，格式: ?token=xxx");
        put("main.regen", "Regenerate", "重新生成");
        put("main.saveSettings", "Save Settings", "保存设置");
        put("main.logs", "Logs", "日志");
        put("main.langLabel", "Language / 语言", "Language / 语言");
        put("main.langEn", "English", "English");
        put("main.langZh", "中文", "中文");

        // ---- TabActivity: toolbar + switcher ----
        put("tab.tabs", "≡ Tabs", "≡ 标签");
        put("tab.urlHint", "Enter URL, e.g. example.com", "输入网址，如 example.com");
        put("tab.go", "Go", "前往");
        put("tab.firstPage", "Already on the first page", "已是第一页");
        put("tab.newFail", "Failed to open a new tab", "新开标签失败");
        put("tab.noReload", "Nothing to reload", "没有可刷新的页面");
        put("tab.needUrl", "Enter a URL first", "请输入网址");
        put("tab.openFailNone", "Open failed: no visible page", "打开失败：没有显示中的页面");
        put("tab.hideAction", "⌄  Hide this WebView (back to headless)", "⌄  隐藏当前 WebView（回到无头模式）");
        put("tab.closeAction", "✕  Close the visible tab", "✕  关闭当前显示的标签页");
        put("tab.noneFound", "No running tabs found", "没有找到运行中的标签页");
        put("tab.switchHead", "Switch WebView · %d tabs", "切换 WebView · 共 %d 个标签");
        put("tab.noneVisible", "No visible tab right now", "当前没有显示中的标签页");
        put("tab.switchFail", "Switch failed: %s", "切换失败: %s");
        put("tab.frontTitle", "samcdp Frontend · %s", "samcdp 前端 · %s");
        put("tab.close", "Close", "关闭");
        put("tab.closeFail", "Close failed", "关闭失败");
        put("tab.closed", "Tab closed", "已关闭标签页");
        put("tab.closing", "Closing…", "关闭中…");
        put("tab.closeFailRemote", "Close failed (the other profile did not respond)", "关闭失败（对方 profile 未响应）");
        put("tab.current", " · current", " · 当前");
        put("tab.ttlIn", "auto-closes in %s", "%s后自动关闭");

        // ---- TunnelManager / CdpService ----
        put("tunnel.needKey", "Subdomain mode requires an aitun Key (pick temp proxy if left empty)",
                "子域名模式需要填入 aitun Key（留空请改选临时代理）");
        put("tunnel.modeMismatch", "Server response does not match the selected mode (chose %s, got: %s) — stopped",
                "服务器返回与所选模式不符（选了%s，实际: %s），已停止");
        put("tunnel.exit", "aitun process exited (exit: %v)", "aitun 进程退出 (exit: %v)");

        // ---- Api.java: in-app API docs page (agent-facing; EN default) ----
        put("api.subtitle", "Remote WebView automation · API 1:1 compatible with desktop · isolated cookie pool per profile",
                "远程 WebView 自动化 · 与桌面版 API 1:1 兼容 · 每 Profile 独立 Cookie 存储池");
        put("api.remoteAddr", "Remote address:", "远程地址:");
        put("api.endpoints", "Endpoints", "接口列表");
        put("api.thMethod", "Method", "方法");
        put("api.thPath", "Path", "路径");
        put("api.thDesc", "Description", "说明");
        put("api.thisPage", "This page (API docs)", "本页（API 文档）");
        put("api.health", "Health check", "健康检查");
        put("api.status", "Server status (incl. tunnel address)", "服务器状态（含隧道地址）");
        put("api.tunnelStatus", "aitun tunnel status", "aitun 隧道状态");
        put("api.tunnelTemp", "Start temp-proxy tunnel", "启动临时代理隧道");
        put("api.tunnelSub", "Start subdomain tunnel", "启动子域名隧道");
        put("api.tunnelStop", "Stop tunnel", "停止隧道");
        put("api.listProfiles", "List profiles", "列出 Profile");
        put("api.start", "Start (isolated-process cookie pool)", "启动（独立进程 Cookie 池）");
        put("api.stop", "Stop (cookies persisted)", "停止（Cookie 持久化）");
        put("api.autoStartNote", "<b>Restart memory (v1.4.1):</b> a profile started from the phone switch (or duration=0) is remembered as auto-start and ALL such profiles are re-activated automatically the next time the app opens; if none was saved, the first profile auto-starts by default. API starts with <code>duration&gt;0</code> are temporary and are NOT restored. Orphan runner processes from a previous session are detected and cleaned up. The desktop app has always started all enabled profiles at launch.",
                "<b>重启记忆（v1.4.1）：</b>通过手机开关启动的 Profile（或 duration=0）会记为自启动，下次打开 App 时<b>全部自动恢复</b>；若没有已保存的 Profile，则默认自动激活第一个。API 以 <code>duration&gt;0</code> 启动的 Profile 是临时的，不会被恢复。上一会话遗留的孤儿 runner 进程会被探测并清理。桌面版启动时始终自动启动全部启用的 Profile。");
        put("api.show", "Show WebView fullscreen (human takeover: CAPTCHA/login)", "全屏显示 WebView（人工接管：验证码/登录）");
        put("api.switch", "Switch this profile's visible WebView (frontend switcher)", "切换该 Profile 显示中的 WebView（前端切换器）");
        put("api.hide", "Hide WebView (back to headless)", "隐藏 WebView（回到无头模式）");
        put("api.visible", "WebView visibility", "WebView 可见状态");
        put("api.frontend", "Multi-profile frontend aggregate (all tabs + visible one)", "多 Profile 前端聚合（全部 tabs + 当前可见项）");
        put("api.listTabs", "List tabs (with auto_close_in_s seconds remaining)", "列出标签页（含 auto_close_in_s 剩余存活秒数）");
        put("api.newTab", "Open a tab (<b>close_after required</b>: auto-closes after N seconds)", "打开标签页（<b>close_after 必填</b>：N 秒后自动关闭）");
        put("api.closeTab", "Close a tab", "关闭标签页");
        put("api.screenshot", "Tab PNG screenshot", "标签页 PNG 截图");
        put("api.eval", "Run JS (body {\"js\":\"...\"})", "执行 JS（body {\"js\":\"...\"}）");
        put("api.cdpPassthrough", "CDP-style passthrough", "CDP 风格透传");
        put("api.caTitle", "⚠ close_after is REQUIRED (prevents tab leaks)", "⚠ close_after 必填（防标签泄漏）");
        put("api.caBody", "Tabs opened via the API MUST pass <code>close_after=&lt;seconds&gt;</code> (1..604800, i.e. up to 7 days); "
                + "the tab is destroyed and its memory reclaimed when the time is up. Missing or invalid values are rejected "
                + "(HTTP 400 with an explanation). Examples: <code>close_after=3600</code> = 1 hour, <code>close_after=600</code> = 10 minutes. "
                + "Tabs opened by a human from the phone UI (➕ / Show WebView / page popups) default to 1 hour, no need to pass it. "
                + "The tab list (/tabs) returns an <code>auto_close_in_s</code> field with the seconds remaining.",
                "通过 API 打开标签页时<strong>必须</strong>传 <code>close_after=秒数</code>（1..604800，即最长 7 天），"
                + "到期标签自动销毁、内存立即回收。缺省或非法值会被拒绝（HTTP 400 并返回说明）。"
                + "示例：<code>close_after=3600</code> = 1 小时，<code>close_after=600</code> = 10 分钟。"
                + "人在手机 UI 上手动开标签（➕ / 显示WebView / 页面弹窗）默认 1 小时自动关闭，无需手填。"
                + "标签列表（/tabs）返回 <code>auto_close_in_s</code> 字段可查询剩余存活秒数。");
        put("api.examples", "Examples", "示例");
        put("api.footer", "Auth: ?token= or Authorization: Bearer · each profile runs in its own process, cookies fully isolated · SamAI Group",
                "鉴权: ?token= 或 Authorization: Bearer · 每 Profile 运行在独立进程，Cookie 完全隔离 · SamAI Group");
    }

    private L() {}

    /** Read the persisted language pref (default: en). Call before building UI. */
    public static void load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("cdpclient", Context.MODE_PRIVATE);
            String v = sp.getString("lang", "en");
            lang = "zh".equals(v) ? "zh" : "en";
        } catch (Exception ignored) {
        }
    }

    /** Persist and apply the language ("en"|"zh"). */
    public static void set(Context ctx, String l) {
        lang = "zh".equals(l) ? "zh" : "en";
        try {
            ctx.getSharedPreferences("cdpclient", Context.MODE_PRIVATE)
                    .edit().putString("lang", lang).apply();
        } catch (Exception ignored) {
        }
    }

    /** Current language code. */
    public static String lang() {
        return lang;
    }

    /** True when rendering Chinese. */
    public static boolean zh() {
        return "zh".equals(lang);
    }

    /** Translate a key; missing keys return the key itself (fail-visible, never crash). */
    public static String t(String key) {
        String[] pair = D.get(key);
        if (pair == null) return key;
        return zh() ? pair[1] : pair[0];
    }

    /** Translate then String.format. */
    public static String f(String key, Object... args) {
        try {
            return String.format(t(key), args);
        } catch (Exception e) {
            return t(key);
        }
    }
}
