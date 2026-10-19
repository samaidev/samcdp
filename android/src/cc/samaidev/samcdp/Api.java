package cc.samaidev.samcdp;

import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.List;

/**
 * REST API surface — 1:1 mirror of desktop SamCDP endpoints:
 *   GET  /?token=                                 API docs page
 *   GET  /api/health?token=                       health probe
 *   GET  /api/status?token=                       server status
 *   GET  /api/profiles?token=                     list profiles
 *   POST /api/profiles/<name>/start?token=&duration=MIN
 *   POST /api/profiles/<name>/stop?token=
 *   GET  /api/profiles/<name>/tabs?token=
 *   POST /api/profiles/<name>/tabs/new?url=URL&token=
 *   DELETE /api/profiles/<name>/tabs/<id>?token=
 *   GET  /api/profiles/<name>/tabs/<id>/screenshot?token=
 *   POST /api/profiles/<name>/tabs/<id>/eval?token=   body {"js":"..."}
 *   GET  /<profile>/json/version|list?token=      CDP-style passthrough
 */
public class Api extends HttpServer {

    private final String token;
    private final long startedAt = System.currentTimeMillis();

    public Api(int port, String token) {
        super(port);
        this.token = token;
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Response err(int status, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Exception ignored) {
        }
        return new Response(status, "application/json; charset=utf-8", o.toString().getBytes());
    }

    private static Response json(JSONObject o) {
        return Response.json(200, o.toString());
    }

    private boolean authed(Request req) {
        String given = req.query.get("token");
        if (given == null || given.isEmpty()) {
            String h = req.header("authorization");
            if (h != null && h.toLowerCase().startsWith("bearer ")) given = h.substring(7).trim();
        }
        if (given == null) return false;
        return MessageDigest.isEqual(given.getBytes(), token.getBytes());
    }

    @Override
    protected Response handle(Request req) throws Exception {
        String path = req.path;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] seg = path.split("/");

        if (!authed(req)) {
            ProfileManager.log("401 " + req.method + " " + path + " (from " +
                    req.header("host") + ")");
            return err(401, "unauthorized: missing or invalid token (?token= or Authorization: Bearer)");
        }

        // ---- docs / health / status
        if (path.isEmpty() || path.equals("/")) {
            if (req.method.equals("GET")) return Response.html(200, docsPage());
            return err(405, "method not allowed");
        }

        if (path.equals("/api/health")) {
            JSONObject o = ok();
            o.put("version", "1.0.0");
            o.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
            return json(o);
        }

        if (path.equals("/api/status")) {
            JSONObject o = ok();
            o.put("app", "SamCDP-Android");
            o.put("version", "1.0.0");
            o.put("ip", CdpService.localIp());
            o.put("port", port());
            o.put("url", "http://" + CdpService.localIp() + ":" + port());
            o.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
            o.put("profiles", ProfileManager.profiles().size());
            JSONArray running = new JSONArray();
            for (Profile p : ProfileManager.profiles()) {
                if (p.running) running.put(p.name);
            }
            o.put("running_profiles", running);
            return json(o);
        }

        if (path.equals("/api/profiles") && req.method.equals("GET")) {
            JSONObject o = ok();
            JSONArray arr = new JSONArray();
            for (Profile p : ProfileManager.profiles()) {
                arr.put(p.toJson(ProfileManager.tabs(p).size()));
            }
            o.put("profiles", arr);
            return json(o);
        }

        // ---- /api/profiles/<name>/...
        if (seg.length >= 3 && seg[1].equals("api") && seg[2].equals("profiles")) {
            String name = urlSegment(seg, 3);
            Profile p = name == null ? null : ProfileManager.find(name);
            if (p == null) return err(404, "profile not found: " + name);

            // /api/profiles/<name>/start|stop
            if (seg.length == 5 && seg[4].equals("start") && req.method.equals("POST")) {
                int duration = 30;
                try {
                    duration = Integer.parseInt(req.query.getOrDefault("duration", "30"));
                } catch (Exception ignored) {
                }
                if (duration <= 0) return err(400, "duration (minutes) is required, e.g. ?duration=30");
                try {
                    ProfileManager.start(CdpService.appContext(), null, p, duration);
                } catch (Exception e) {
                    return err(409, e.getMessage());
                }
                JSONObject o = ok();
                o.put("profile", p.name);
                o.put("duration_minutes", duration);
                o.put("auto_stop_in_s", p.autoStopAtMs > 0 ? Math.max(0, (p.autoStopAtMs - System.currentTimeMillis()) / 1000) : 0);
                return json(o);
            }

            if (seg.length == 5 && seg[4].equals("stop") && req.method.equals("POST")) {
                ProfileManager.stop(p);
                return json(ok());
            }

            // /api/profiles/<name>/tabs
            if (seg.length == 5 && seg[4].equals("tabs") && req.method.equals("GET")) {
                JSONObject o = ok();
                JSONArray arr = new JSONArray();
                for (TabItem t : ProfileManager.tabs(p)) {
                    arr.put(tabJson(p, t));
                }
                o.put("tabs", arr);
                return json(o);
            }

            if (seg.length == 5 && seg[4].equals("tabs") && req.method.equals("POST")) {
                String url = req.query.get("url");
                if (url == null || url.isEmpty()) return err(400, "url query param required");
                if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")
                        && !url.startsWith("about:")) url = "https://" + url;
                TabItem t = ProfileManager.openTab(p, url);
                JSONObject o = ok();
                o.put("tab", tabJson(p, t));
                return json(o);
            }

            // /api/profiles/<name>/tabs/<id>[/screenshot|/eval]
            if (seg.length >= 6 && seg[4].equals("tabs")) {
                String id = urlSegment(seg, 5);
                TabItem t = id == null ? null : ProfileManager.findTab(p, id);
                if (t == null) return err(404, "tab not found: " + id);

                if (seg.length == 6 && req.method.equals("DELETE")) {
                    boolean done = ProfileManager.closeTab(p, id);
                    return done ? json(ok()) : err(404, "tab not found: " + id);
                }

                if (seg.length == 7 && seg[6].equals("screenshot") && req.method.equals("GET")) {
                    byte[] png = ProfileManager.screenshot(t);
                    return new Response(200, "image/png", png);
                }

                if (seg.length == 7 && seg[6].equals("eval") && req.method.equals("POST")) {
                    String js = req.query.getOrDefault("js", "");
                    if (js.isEmpty() && req.body.length > 0) {
                        try {
                            JSONObject b = new JSONObject(req.bodyString());
                            js = b.optString("js", "");
                        } catch (Exception ignored) {
                        }
                    }
                    if (js.isEmpty()) return err(400, "js required (?js=... or body {\"js\":\"...\"})");
                    try {
                        String raw = ProfileManager.eval(t, js);
                        JSONObject o = ok();
                        try {
                            o.put("result", new org.json.JSONTokener(raw).nextValue());
                        } catch (Exception e) {
                            o.put("result", raw);
                        }
                        return json(o);
                    } catch (Exception e) {
                        return err(500, e.getMessage());
                    }
                }
            }
        }

        // ---- /<profile>/json/version|list  (CDP-style passthrough)
        if (seg.length == 3 && seg[2].startsWith("json")) {
            Profile p = ProfileManager.find(seg[1]);
            if (p == null) return err(404, "profile not found: " + seg[1]);
            if (seg[2].equals("json") || seg[2].equals("json/list")) {
                return cdpList(p);
            }
            if (seg[2].equals("json/version")) return cdpVersion(p);
        }

        return err(404, "not found: " + req.method + " " + req.path);
    }

    private static String urlSegment(String[] seg, int idx) {
        if (idx < seg.length) {
            try {
                return java.net.URLDecoder.decode(seg[idx], "UTF-8");
            } catch (Exception e) {
                return seg[idx];
            }
        }
        return null;
    }

    private JSONObject tabJson(Profile p, TabItem t) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("title", t.title);
            o.put("url", t.url);
            o.put("type", "page");
            o.put("profile", p.name);
            o.put("opened_at_s", t.openedAtMs / 1000);
            o.put("screenshot", "/api/profiles/" + p.name + "/tabs/" + t.id + "/screenshot");
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private Response cdpList(Profile p) {
        if (!p.running) return err(409, "profile not running");
        String real = ProfileManager.devtoolsList();
        JSONObject o = ok();
        try {
            if (real != null) {
                o.put("source", "webview-devtools");
                o.put("targets", new org.json.JSONTokener(real).nextValue());
            } else {
                JSONArray arr = new JSONArray();
                for (TabItem t : ProfileManager.tabs(p)) arr.put(tabJson(p, t));
                o.put("source", "registry");
                o.put("targets", arr);
            }
        } catch (Exception e) {
            return err(500, e.getMessage());
        }
        return json(o);
    }

    private Response cdpVersion(Profile p) {
        JSONObject o = new JSONObject();
        try {
            o.put("Browser", "SamCDP-WebView/1.0.0");
            o.put("Protocol-Version", "1.3");
            o.put("User-Agent", "SamCDP-Android/1.0.0 (Android " + android.os.Build.VERSION.RELEASE + ")");
            o.put("Android", android.os.Build.VERSION.RELEASE);
            o.put("Profile", p.name);
            o.put("Running", p.running);
        } catch (Exception ignored) {
        }
        return json(o);
    }

    // ------------------------------------------------------------------ docs page

    private String docsPage() {
        String ip = CdpService.localIp();
        String base = "http://" + ip + ":" + port();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>SamCDP — Android API</title><style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{background:#0a0a0a;color:#e0e0e0;font-family:monospace;padding:24px;line-height:1.6}");
        sb.append("h1{color:#4ade80;font-size:20px}h2{color:#4ade80;font-size:15px;margin:18px 0 8px}");
        sb.append(".muted{color:#888;font-size:12px}code{background:#1a1a1a;padding:2px 6px;border-radius:4px;font-size:12px}");
        sb.append("table{border-collapse:collapse;width:100%;font-size:12px;margin-top:6px}");
        sb.append("td,th{border:1px solid #2a2a2a;padding:6px 8px;text-align:left}th{color:#4ade80}");
        sb.append(".u{color:#4ade80;word-break:break-all}");
        sb.append("</style></head><body>");
        sb.append("<h1>SamCDP — Android v1.0.0</h1>");
        sb.append("<p class=\"muted\">Remote WebView automation for AI agents · desktop-compatible REST API<br>");
        sb.append("Base URL: <span class=\"u\">").append(base).append("</span></p>");
        sb.append("<h2>Endpoints</h2><table>");
        sb.append("<tr><th>Method</th><th>Endpoint</th><th>Description</th></tr>");
        row(sb, "GET", "/?token=xxx", "This docs page");
        row(sb, "GET", "/api/health?token=xxx", "Health probe");
        row(sb, "GET", "/api/status?token=xxx", "Server status (ip/port/uptime)");
        row(sb, "GET", "/api/profiles?token=xxx", "List all profiles");
        row(sb, "POST", "/api/profiles/&lt;name&gt;/start?token=xxx&duration=MIN", "Start profile (duration required, minutes)");
        row(sb, "POST", "/api/profiles/&lt;name&gt;/stop?token=xxx", "Stop profile");
        row(sb, "GET", "/api/profiles/&lt;name&gt;/tabs?token=xxx", "List tabs");
        row(sb, "POST", "/api/profiles/&lt;name&gt;/tabs/new?url=URL&token=xxx", "Open a new tab (headless WebView)");
        row(sb, "DELETE", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;?token=xxx", "Close a tab");
        row(sb, "GET", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;/screenshot?token=xxx", "PNG screenshot of the tab");
        row(sb, "POST", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;/eval?token=xxx", "Run JS in the tab (body {\"js\":\"...\"})");
        row(sb, "GET", "/&lt;profile&gt;/json/version?token=xxx", "CDP-style version info");
        row(sb, "GET", "/&lt;profile&gt;/json/list?token=xxx", "CDP-style target list");
        sb.append("</table>");
        sb.append("<h2>Example</h2>");
        sb.append("<code>curl -X POST \"").append(base)
                .append("/api/profiles/agent-1/start?token=").append(token)
                .append("&amp;duration=30\"</code><br>");
        sb.append("<code>curl -X POST \"").append(base)
                .append("/api/profiles/agent-1/tabs/new?url=https://example.com&amp;token=").append(token).append("\"</code><br>");
        sb.append("<code>curl \"").append(base).append("/api/profiles/agent-1/tabs?token=").append(token).append("\"</code>");
        sb.append("<p class=\"muted\">Auth: ?token= query param or Authorization: Bearer header · ")
                .append("Generated ").append(DateFormat.format("yyyy-MM-dd HH:mm", System.currentTimeMillis())).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String m, String e, String d) {
        sb.append("<tr><td>").append(m).append("</td><td><span class=\"u\">").append(e)
                .append("</span></td><td>").append(d).append("</td></tr>");
    }
}
