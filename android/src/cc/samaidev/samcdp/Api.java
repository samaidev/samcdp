package cc.samaidev.samcdp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * REST API surface — desktop-compatible + Android extras:
 *   GET  /                                        API docs page
 *   GET  /api/health /api/status                  health & status (incl. tunnel)
 *   GET  /api/tunnel                              tunnel state
 *   POST /api/tunnel/start?mode=temp|subdomain&key=ait_xxx
 *   POST /api/tunnel/stop
 *   GET  /api/profiles
 *   POST /api/profiles/<name>/start?duration=MIN | /stop
 *   GET|POST /api/profiles/<name>/tabs | /tabs/new?url=&ua=
 *   DELETE /api/profiles/<name>/tabs/<id>
 *   GET|POST /api/profiles/<name>/tabs/<id>/screenshot | /eval
 *   GET  /<profile>/json/version|list             CDP-style passthrough
 */
public class Api extends HttpServer {

    // volatile：UI 线程重新生成/手填 token 后 setToken() 热生效，无需重启服务
    private volatile String token;
    private final long startedAt = System.currentTimeMillis();

    public Api(int port, String token) {
        super(port);
        this.token = token;
    }

    /** Hot-swap the auth token; takes effect on the very next request. */
    public void setToken(String t) {
        if (t == null || t.isEmpty()) return;
        this.token = t;
    }

    private static Response json(int status, JSONObject o) {
        return Response.json(status, o.toString());
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
        return json(status, o);
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
            return err(401, "unauthorized: missing or invalid token (?token= or Authorization: Bearer)");
        }

        if (path.isEmpty() || path.equals("/")) {
            if (req.method.equals("GET")) return Response.html(200, docsPage());
            return err(405, "method not allowed");
        }

        if (path.equals("/api/health")) {
            JSONObject o = ok();
            o.put("version", "1.4.8");
            o.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
            return json(200, o);
        }

        if (path.equals("/api/status")) {
            JSONObject o = ok();
            o.put("app", "samcdp-android");
            o.put("version", "1.4.8");
            o.put("ip", CdpService.localIp());
            o.put("port", port());
            o.put("lan_url", "http://" + CdpService.localIp() + ":" + port());
            JSONObject t = new JSONObject(TunnelManager.statusJson());
            // 远程地址规则与 UI 一致：在线→隧道地址；连接中→null（不给局域网地址造成误导）；
            // 未启用→局域网地址（唯一可达入口）
            if (TunnelManager.state() == TunnelManager.State.ONLINE) {
                o.put("remote_url", t.optString("url"));
            } else if (TunnelManager.state() == TunnelManager.State.CONNECTING) {
                o.put("remote_url", JSONObject.NULL);
            } else {
                o.put("remote_url", "http://" + CdpService.localIp() + ":" + port());
            }
            o.put("tunnel", t);
            o.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
            JSONArray running = new JSONArray();
            int n = 0;
            for (Profile p : ProfileManager.profiles()) {
                if (p.running) {
                    running.put(p.name);
                    n++;
                }
            }
            o.put("profiles", ProfileManager.profiles().size());
            o.put("running_profiles", running);
            o.put("running_count", n);
            return json(200, o);
        }

        if (path.equals("/api/tunnel")) {
            return json(200, new JSONObject(TunnelManager.statusJson()));
        }

        // v1.4.2: downloads across ALL profiles — running ones are proxied to their
        // runner (in-memory registry), stopped ones are read from disk meta.json.
        if (path.equals("/api/downloads") && req.method.equals("GET")) {
            String want = req.query.get("profile");
            JSONObject o = ok();
            JSONArray arr = new JSONArray();
            for (Profile p : ProfileManager.profiles()) {
                if (want != null && !want.isEmpty() && !want.equals(p.name)) continue;
                JSONObject po = new JSONObject();
                try {
                    po.put("profile", p.name);
                    po.put("running", p.running);
                    String body;
                    if (p.running) {
                        body = new String(ProfileManager.downloads(p).payload, java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        body = Downloads.diskListJson(CdpService.appContext(), p.slot);
                    }
                    JSONObject parsed = new JSONObject(body);
                    JSONArray dl = parsed.optJSONArray("downloads");
                    po.put("downloads", dl == null ? new JSONArray() : dl);
                } catch (Exception e) {
                    po.put("downloads", new JSONArray());
                    po.put("error", e.getMessage());
                }
                arr.put(po);
            }
            o.put("profiles", arr);
            return json(200, o);
        }

        // v1.4.4: push a file INTO a profile's downloads store from outside (PC,
        // agent, curl through LAN or the aitun tunnel). Raw body = file bytes;
        // ?name= is the file name; ?profile= picks the target (default: first
        // running profile, else the first profile). Running profiles get the
        // bytes proxied to their runner (in-memory registry + meta.json);
        // stopped profiles are written to disk directly (no meta.json writer).
        if (path.equals("/api/downloads/upload") && req.method.equals("POST")) {
            String want = req.query.get("profile");
            String name = req.query.get("name");
            if (name == null || name.trim().isEmpty()) name = "upload.bin";
            Profile target = null;
            if (want != null && !want.isEmpty()) {
                for (Profile p : ProfileManager.profiles()) if (p.name.equals(want)) { target = p; break; }
                if (target == null) return err(404, "profile not found: " + want);
            } else {
                for (Profile p : ProfileManager.profiles()) { if (p.running) { target = p; break; } }
                if (target == null && !ProfileManager.profiles().isEmpty()) target = ProfileManager.profiles().get(0);
            }
            if (target == null) return err(404, "no profiles configured");
            if (req.body == null || req.body.length == 0)
                return err(400, "empty body - send the raw file bytes as the request body (Content-Type: application/octet-stream)");
            if (target.running) {
                return toResp(ProfileManager.downloadUploadFile(target, name, req.body));
            }
            return json(200, new JSONObject(Downloads.saveUploadedToDisk(
                    CdpService.appContext(), target.slot, target.name, req.body, name)));
        }

        // Aggregated multi-profile frontend state: every running profile's tabs +
        // which WebView is currently visible — the data behind the on-phone
        // switcher pill, usable for remote control too.
        if (path.equals("/api/frontend") && req.method.equals("GET")) {
            JSONObject o = ok();
            JSONArray arr = new JSONArray();
            for (Profile p : ProfileManager.profiles()) {
                JSONObject po = new JSONObject();
                try {
                    po.put("profile", p.name);
                    po.put("running", p.running);
                    po.put("slot", p.slot);
                    boolean vis = false;
                    String visTab = null, visUrl = null, visTitle = null;
                    JSONArray tabsArr = new JSONArray();
                    if (p.running) {
                        ProfileManager.ProxyResult v = ProfileManager.visible(p);
                        try {
                            JSONObject vj = new JSONObject(new String(v.payload));
                            vis = vj.optBoolean("visible");
                            visTab = vj.has("tab") && !vj.isNull("tab") ? vj.optString("tab") : null;
                            visUrl = vj.optString("url", null);
                            visTitle = vj.optString("title", null);
                        } catch (Exception ignored) {
                        }
                        ProfileManager.ProxyResult tr = ProfileManager.tabs(p);
                        try {
                            JSONArray ta = new JSONObject(new String(tr.payload)).optJSONArray("tabs");
                            if (ta != null) tabsArr = ta;
                        } catch (Exception ignored) {
                        }
                    }
                    po.put("visible", vis);
                    po.put("visible_tab", visTab == null ? JSONObject.NULL : visTab);
                    po.put("visible_url", visUrl == null ? JSONObject.NULL : visUrl);
                    po.put("visible_title", visTitle == null ? JSONObject.NULL : visTitle);
                    po.put("tabs", tabsArr);
                } catch (Exception ignored) {
                }
                arr.put(po);
            }
            o.put("webviews", arr);
            return json(200, o);
        }

        if (path.equals("/api/tunnel/start") && req.method.equals("POST")) {
            String mode = req.query.getOrDefault("mode", "temp");
            String key = req.query.getOrDefault("key", "");
            if ("subdomain".equals(mode) && (key == null || key.isEmpty())) {
                return err(400, "subdomain mode requires key (ait_xxx)");
            }
            TunnelManager.start(CdpService.appContext(), mode, key, port());
            return json(200, new JSONObject(TunnelManager.statusJson()));
        }

        if (path.equals("/api/tunnel/stop") && req.method.equals("POST")) {
            TunnelManager.stop();
            return json(200, new JSONObject(TunnelManager.statusJson()));
        }

        if (path.equals("/api/profiles/add") && req.method.equals("POST")) {
            String name = req.query.get("name");
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,32}")) {
                return err(400, "name required (a-zA-Z0-9_-, max 32)");
            }
            if (ProfileManager.find(name) != null) return err(409, "profile already exists: " + name);
            if (ProfileManager.profiles().size() >= ProfileManager.MAX_PROFILES) {
                return err(409, "max " + ProfileManager.MAX_PROFILES + " profiles (one process each)");
            }
            int port = 9334 + ProfileManager.profiles().size();
            try {
                port = Integer.parseInt(req.query.getOrDefault("port", String.valueOf(port)));
            } catch (Exception ignored) {
            }
            Profile np = new Profile(name, port, true);
            ProfileManager.profiles().add(np);
            ProfileManager.saveProfiles();
            ProfileManager.dispatchLog("[config] profile added via API: " + name + " (port " + port + ")");
            return json(200, np.toJson(0));
        }

        if (path.equals("/api/profiles") && req.method.equals("GET")) {
            JSONObject o = ok();
            JSONArray arr = new JSONArray();
            for (Profile p : ProfileManager.profiles()) {
                int tabs = 0;
                if (p.running) {
                    ProfileManager.ProxyResult r = ProfileManager.tabs(p);
                    try {
                        tabs = new JSONObject(new String(r.payload)).optJSONArray("tabs") == null
                                ? 0 : new JSONObject(new String(r.payload)).optJSONArray("tabs").length();
                    } catch (Exception ignored) {
                    }
                }
                arr.put(p.toJson(tabs));
            }
            o.put("profiles", arr);
            return json(200, o);
        }

        if (seg.length >= 3 && seg[1].equals("api") && seg[2].equals("profiles")) {
            String name = Api.urlSegment(seg, 3);
            Profile p = name == null ? null : ProfileManager.find(name);
            if (p == null) return err(404, "profile not found: " + name);

            if (seg.length == 4 && req.method.equals("DELETE")) {
                ProfileManager.stop(CdpService.appContext(), p);
                ProfileManager.profiles().remove(p);
                ProfileManager.saveProfiles();
                ProfileManager.dispatchLog("[config] profile deleted via API: " + p.name);
                return json(200, ok());
            }

            if (seg.length == 5 && seg[4].equals("start") && req.method.equals("POST")) {
                int duration = 30;
                try {
                    duration = Integer.parseInt(req.query.getOrDefault("duration", "30"));
                } catch (Exception ignored) {
                }
                if (duration <= 0) return err(400, "duration (minutes) is required, e.g. ?duration=30");
                try {
                    ProfileManager.start(CdpService.appContext(), p, duration);
                } catch (Exception e) {
                    return err(409, e.getMessage());
                }
                JSONObject o = ok();
                o.put("profile", p.name);
                o.put("duration_minutes", duration);
                o.put("auto_stop_in_s", p.autoStopAtMs > 0
                        ? Math.max(0, (p.autoStopAtMs - System.currentTimeMillis()) / 1000) : 0);
                return json(200, o);
            }

            if (seg.length == 5 && seg[4].equals("stop") && req.method.equals("POST")) {
                ProfileManager.stop(CdpService.appContext(), p);
                return json(200, ok());
            }

            if (seg.length == 5 && seg[4].equals("show") && req.method.equals("POST")) {
                return toResp(ProfileManager.show(p, req.query.get("tab")));
            }

            if (seg.length == 5 && seg[4].equals("switch") && req.method.equals("POST")) {
                return toResp(ProfileManager.switchTab(p, req.query.get("tab")));
            }

            if (seg.length == 5 && seg[4].equals("hide") && req.method.equals("POST")) {
                return toResp(ProfileManager.hide(p));
            }

            if (seg.length == 5 && seg[4].equals("visible") && req.method.equals("GET")) {
                return toResp(ProfileManager.visible(p));
            }

            if (seg.length == 5 && seg[4].equals("tabs") && req.method.equals("GET")) {
                return toResp(ProfileManager.tabs(p));
            }

            if (seg.length == 6 && seg[4].equals("tabs") && seg[5].equals("new") && req.method.equals("POST")) {
                String url = req.query.get("url");
                if (url == null || url.isEmpty()) return err(400, "url query param required");
                if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")
                        && !url.startsWith("about:")) url = "https://" + url;
                // v1.3.9: close_after is REQUIRED (seconds until auto-close).
                // Agents must declare the tab's lifetime — tabs opened without one
                // leak WebView memory until the profile process dies. Human opens
                // from the phone UI default to 1 hour (see TabEngine.MANUAL_TAB_TTL_S).
                long closeAfter = -1;
                String caRaw = req.query.get("close_after");
                if (caRaw != null) {
                    try {
                        closeAfter = Long.parseLong(caRaw.trim());
                    } catch (Exception ignored) {
                    }
                }
                if (closeAfter <= 0) {
                    return err(400, "close_after is required: number of SECONDS until this tab auto-closes (1..604800). "
                            + "Example: close_after=3600 keeps the tab for 1 hour. "
                            + "API callers must declare how long the tab lives to prevent leaked tabs from exhausting device memory; "
                            + "tabs opened by a human from the phone UI default to 1 hour.");
                }
                if (closeAfter > 604800) {
                    return err(400, "close_after too large: max 604800 seconds (7 days)");
                }
                return toResp(ProfileManager.openTab(p, url, req.query.get("ua"), closeAfter));
            }

            if (seg.length >= 6 && seg[4].equals("tabs")) {
                String id = Api.urlSegment(seg, 5);

                if (seg.length == 6 && req.method.equals("DELETE")) {
                    return toResp(ProfileManager.closeTab(p, id));
                }
                if (seg.length == 7 && seg[6].equals("screenshot") && req.method.equals("GET")) {
                    ProfileManager.ProxyResult r = ProfileManager.screenshot(p, id);
                    return toResp(r);
                }
                if (seg.length == 7 && seg[6].equals("eval") && req.method.equals("POST")) {
                    String js = req.query.getOrDefault("js", "");
                    if (js.isEmpty() && req.body.length > 0) {
                        try {
                            js = new JSONObject(req.bodyString()).optString("js", "");
                        } catch (Exception ignored) {
                        }
                    }
                    if (js.isEmpty()) return err(400, "js required (?js=... or body {\"js\":\"...\"})");
                    return toResp(ProfileManager.eval(p, id, js));
                }
            }

            // ---- v1.4.2: per-profile downloads ----
            // /api/profiles/<name>/downloads                     list
            // /api/profiles/<name>/downloads/<id>                DELETE
            // /api/profiles/<name>/downloads/<id>/file           pull raw bytes
            // /api/profiles/<name>/downloads/<id>/upload         attach to page file input
            if (seg.length >= 5 && seg[4].equals("downloads")) {
                if (seg.length == 5 && req.method.equals("GET")) {
                    return toResp(ProfileManager.downloads(p));
                }
                if (seg.length == 6 && req.method.equals("DELETE")) {
                    return toResp(ProfileManager.downloadDelete(p, seg[5]));
                }
                if (seg.length == 7 && seg[6].equals("file") && req.method.equals("GET")) {
                    return toResp(ProfileManager.downloadFile(p, seg[5]));
                }
                if (seg.length == 7 && seg[6].equals("upload") && req.method.equals("POST")) {
                    String tab = req.query.get("tab");
                    String selector = req.query.get("selector");
                    if ((tab == null || selector == null) && req.body.length > 0) {
                        try {
                            JSONObject jb = new JSONObject(req.bodyString());
                            if (tab == null) tab = jb.optString("tab", null);
                            if (selector == null) selector = jb.optString("selector", null);
                        } catch (Exception ignored) {
                        }
                    }
                    if (tab == null || tab.isEmpty()) return err(400, "tab required (?tab=<tabId>)");
                    if (selector == null || selector.isEmpty())
                        return err(400, "selector required (?selector=<css> of the page's <input type=file>)");
                    return toResp(ProfileManager.downloadUpload(p, seg[5], tab, selector));
                }
            }
        }

        // /<profile>/json[/version|/list]
        if (seg.length >= 3 && seg[2] != null && seg[2].equals("json")) {
            Profile p = ProfileManager.find(seg[1]);
            if (p == null) return err(404, "profile not found: " + seg[1]);
            if (seg.length == 4 && seg[3].equals("version")) {
                JSONObject o = new JSONObject();
                try {
                    o.put("Browser", "samcdp-webview/1.4.8");
                    o.put("Protocol-Version", "1.3");
                    o.put("User-Agent", "samcdp-android/1.4.8 (Android " + android.os.Build.VERSION.RELEASE + ")");
                    o.put("Android", android.os.Build.VERSION.RELEASE);
                    o.put("Profile", p.name);
                    o.put("Running", p.running);
                    o.put("IsolatedCookieJar", p.running);
                } catch (Exception ignored) {
                }
                return json(200, o);
            }
            if (seg.length == 3 || (seg.length == 4 && seg[3].equals("list"))) {
                if (!p.running) return err(409, "profile not running");
                ProfileManager.ProxyResult r = ProfileManager.tabs(p);
                JSONObject o = ok();
                try {
                    o.put("source", "isolated-runner");
                    JSONArray tabs = new JSONObject(new String(r.payload)).optJSONArray("tabs");
                    o.put("targets", tabs == null ? new JSONArray() : tabs);
                } catch (Exception e) {
                    o.put("targets", new JSONArray());
                }
                return json(200, o);
            }
        }

        return err(404, "not found: " + req.method + " " + req.path);
    }

    private static Response toResp(ProfileManager.ProxyResult r) {
        return new Response(r.status, r.contentType, r.payload);
    }

    static String urlSegment(String[] seg, int idx) {
        if (idx < seg.length) {
            try {
                return java.net.URLDecoder.decode(seg[idx], "UTF-8");
            } catch (Exception e) {
                return seg[idx];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ docs page (desktop style)

    private String docsPage() {
        String lan = "http://" + CdpService.localIp() + ":" + port();
        // 与 UI 同规则：在线→隧道地址；连接中→占位提示；未启用→局域网地址
        String remote;
        if (TunnelManager.state() == TunnelManager.State.ONLINE) {
            remote = TunnelManager.url();
        } else if (TunnelManager.state() == TunnelManager.State.CONNECTING) {
            remote = L.t("main.tunnelConnecting");
        } else {
            remote = lan;
        }
        // curl 示例始终用实际可达地址（在线时即隧道地址）
        String exampleBase = TunnelManager.state() == TunnelManager.State.ONLINE
                ? TunnelManager.url() : lan;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=").append('"').append(L.lang()).append('"')
          .append("><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>samcdp Android API</title><style>");
        sb.append("body{background:#FFF3E2;color:#4A3B2A;font-family:-apple-system,system-ui,sans-serif;padding:24px;line-height:1.7;max-width:860px;margin:0 auto}");
        sb.append("h1{color:#D2610F;font-size:22px}h2{color:#D2610F;font-size:16px;margin:20px 0 8px}");
        sb.append(".muted{color:#A8957D;font-size:12px}code{background:#FFFCF3;border:1px solid #EFD9AE;padding:2px 7px;border-radius:5px;font-size:12px;font-family:monospace}");
        sb.append("table{border-collapse:collapse;width:100%;font-size:13px;margin-top:8px;background:#FFFAF0}");
        sb.append("td,th{border:1px solid #F2DDB2;padding:7px 10px;text-align:left}th{color:#D2610F;background:#FFF8EA}");
        sb.append(".u{color:#2F9E44;font-family:monospace;word-break:break-all}");
        sb.append("</style></head><body>");
        sb.append("<h1>⚙ samcdp Android v1.4.8</h1>");
        sb.append("<p class=\"muted\">").append(L.t("api.subtitle")).append("<br>");
        sb.append(L.t("api.remoteAddr")).append(" <span class=\"u\">").append(remote).append("</span></p>");
        sb.append("<h2>").append(L.t("api.endpoints")).append("</h2><table>");
        sb.append("<tr><th>").append(L.t("api.thMethod")).append("</th><th>").append(L.t("api.thPath"))
          .append("</th><th>").append(L.t("api.thDesc")).append("</th></tr>");
        row(sb, "GET", "/?token=xxx", L.t("api.thisPage"));
        row(sb, "GET", "/api/health?token=xxx", L.t("api.health"));
        row(sb, "GET", "/api/status?token=xxx", L.t("api.status"));
        row(sb, "GET", "/api/tunnel?token=xxx", L.t("api.tunnelStatus"));
        row(sb, "POST", "/api/tunnel/start?mode=temp&token=xxx", L.t("api.tunnelTemp"));
        row(sb, "POST", "/api/tunnel/start?mode=subdomain&key=ait_xxx&token=xxx", L.t("api.tunnelSub"));
        row(sb, "POST", "/api/tunnel/stop?token=xxx", L.t("api.tunnelStop"));
        row(sb, "GET", "/api/profiles?token=xxx", L.t("api.listProfiles"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/start?token=xxx&duration=MIN", L.t("api.start"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/stop?token=xxx", L.t("api.stop"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/show?tab=ID&amp;token=xxx", L.t("api.show"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/switch?tab=ID&amp;token=xxx", L.t("api.switch"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/hide?token=xxx", L.t("api.hide"));
        row(sb, "GET", "/api/profiles/&lt;name&gt;/visible?token=xxx", L.t("api.visible"));
        row(sb, "GET", "/api/frontend?token=xxx", L.t("api.frontend"));
        row(sb, "GET", "/api/profiles/&lt;name&gt;/tabs?token=xxx", L.t("api.listTabs"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/tabs/new?url=URL&close_after=SEC&ua=AGENT&token=xxx", L.t("api.newTab"));
        row(sb, "DELETE", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;?token=xxx", L.t("api.closeTab"));
        row(sb, "GET", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;/screenshot?token=xxx", L.t("api.screenshot"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/tabs/&lt;id&gt;/eval?token=xxx", L.t("api.eval"));
        row(sb, "GET", "/api/downloads?token=xxx", L.t("api.dlAgg"));
        row(sb, "GET", "/api/profiles/&lt;name&gt;/downloads?token=xxx", L.t("api.dlList"));
        row(sb, "GET", "/api/profiles/&lt;name&gt;/downloads/&lt;id&gt;/file?token=xxx", L.t("api.dlFile"));
        row(sb, "DELETE", "/api/profiles/&lt;name&gt;/downloads/&lt;id&gt;?token=xxx", L.t("api.dlDelete"));
        row(sb, "POST", "/api/profiles/&lt;name&gt;/downloads/&lt;id&gt;/upload?tab=ID&amp;selector=CSS&amp;token=xxx", L.t("api.dlUpload"));
        row(sb, "POST", "/api/downloads/upload?name=file.bin&amp;profile=name&amp;token=xxx", L.t("api.dlRowUp"));
        row(sb, "GET", "/&lt;profile&gt;/json/version | /json/list?token=xxx", L.t("api.cdpPassthrough"));
        sb.append("</table>");
        sb.append("<p class=\"muted\">").append(L.t("api.autoStartNote")).append("</p>");
        sb.append("<h2>").append(L.t("api.dlTitle")).append("</h2>");
        sb.append("<p>").append(L.t("api.dlBody")).append("</p>");
        // v1.4.4: upload form — pushes files from THIS browser (PC via LAN/tunnel,
        // or the phone itself) into a profile's downloads store. Relative URL so
        // it works both directly on :9334 and through the aitun tunnel prefix.
        sb.append("<h3>").append(L.t("api.ulTitle")).append("</h3>");
        sb.append("<p>").append(L.t("api.ulBody")).append("</p>");
        sb.append("<p><input type=file id=ulp multiple> <button type=button onclick=\"ulGo()\">")
          .append(L.t("api.ulBtn")).append("</button></p>");
        // v1.4.5: live per-file status — picking a file auto-uploads immediately and the
        // result area shows ↑ uploading / ✓ ok / ✗ failed lines as they happen, so a user
        // can always tell whether the transfer really ran (works over LAN and tunnel).
        sb.append("<pre id=ulr style=\"white-space:pre-wrap;background:#FFFCF3;border:1px solid #EFD9AE;padding:8px;font-size:12px;min-height:18px\"> </pre>");
        sb.append("<script>"
                + "function ulR(out){document.getElementById('ulr').textContent=out.join('\\n');}"
                + "function ulGo(){var fs=document.getElementById('ulp').files;"
                + "var tk=new URLSearchParams(location.search).get('token')||'';"
                + "var out=[];var el=document.getElementById('ulr');"
                + "if(!fs.length){el.textContent='no file selected \\u2014 pick a file first';return;}"
                + "var ch=Promise.resolve();var okCnt=0;"
                + "for(var i=0;i<fs.length;i++){(function(f,i){ch=ch.then(function(){"
                + "out[i]='\\u2191 '+f.name+' ('+f.size+'B) uploading...';ulR(out);"
                + "return fetch('api/downloads/upload?name='+encodeURIComponent(f.name)+'&token='+encodeURIComponent(tk),"
                + "{method:'POST',body:f,headers:{'Content-Type':'application/octet-stream'}})"
                + ".then(function(r){return r.json()})"
                + ".then(function(j){if(j.ok){okCnt++;out[i]='\\u2713 '+f.name+' uploaded -> id='+j.download.id+' ('+j.download.size+'B)';}"
                + "else{out[i]='\\u2717 '+f.name+' FAILED: '+(j.error||('http '+r.status))+' \\u2014 press Upload to retry';}ulR(out);})"
                + ".catch(function(e){out[i]='\\u2717 '+f.name+' FAILED: '+e+' \\u2014 press Upload to retry';ulR(out);});});})(fs[i],i);}"
                + "ch.then(function(){if(okCnt===fs.length){document.getElementById('ulp').value='';out.push('\\u2014 all done \\u2014');}ulR(out);});}"
                + "document.getElementById('ulp').addEventListener('change',function(){"
                + "var fs=this.files;if(!fs.length)return;"
                + "document.getElementById('ulr').textContent='picked '+fs[0].name+(fs.length>1?(' +'+(fs.length-1)+' more'):'')+' ('+fs[0].size+'B) \\u2014 uploading...';ulGo();});"
                + "</script>");
        sb.append("<h2>").append(L.t("api.caTitle")).append("</h2>");
        sb.append("<p>").append(L.t("api.caBody")).append("</p>");
        sb.append("<h2>").append(L.t("api.examples")).append("</h2>");
        sb.append("<code>curl -X POST \"").append(exampleBase).append("/api/profiles/agent-1/start?duration=30&amp;token=&lt;token&gt;\"</code><br><br>");
        sb.append("<code>curl -X POST \"").append(exampleBase).append("/api/profiles/agent-1/tabs/new?url=https://www.baidu.com&amp;close_after=3600&amp;token=&lt;token&gt;\"</code>");
        sb.append("<p class=\"muted\">").append(L.t("api.footer")).append("</p></body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String m, String e, String d) {
        sb.append("<tr><td>").append(m).append("</td><td><span class=\"u\">").append(e)
                .append("</span></td><td>").append(d).append("</td></tr>");
    }
}
