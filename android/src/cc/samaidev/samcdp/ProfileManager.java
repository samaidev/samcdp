package cc.samaidev.samcdp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Core engine: desktop-parity profile/tab management built on headless WebViews.
 * All WebView work is marshalled to the main thread; API threads block on latches.
 */
public class ProfileManager {

    public interface LogSink {
        void onLog(String line);
    }

    private static final Object LOCK = new Object();
    private static final Map<String, List<TabItem>> TABS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> VISITED = new ConcurrentHashMap<>();
    private static final Map<String, Runnable> TIMERS = new ConcurrentHashMap<>();
    private static final List<Profile> PROFILES = new CopyOnWriteArrayList<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile LogSink logSink;
    private static volatile Context appContext;
    private static volatile Prefs prefs;

    public static void log(String line) {
        String stamped = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                + " " + line;
        android.util.Log.i("SamCDP", line);
        LogSink s = logSink;
        if (s != null) s.onLog(stamped);
    }

    public static void setLogSink(LogSink sink) {
        logSink = sink;
    }

    public static List<Profile> profiles() {
        return PROFILES;
    }

    public static Profile find(String name) {
        for (Profile p : PROFILES) if (p.name.equals(name)) return p;
        return null;
    }

    public static void loadProfiles(Prefs p) {
        prefs = p;
        PROFILES.clear();
        JSONArray arr = p.profilesJson();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                PROFILES.add(new Profile(o.getString("name"), o.optInt("port", 9334 + i),
                        o.optBoolean("enabled", true)));
            } catch (Exception ignored) {
            }
        }
        if (PROFILES.isEmpty()) PROFILES.add(new Profile("agent-1", 9334, true));
    }

    // ------------------------------------------------------------------ utils

    private static void runAndWait(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static List<TabItem> tabsOf(Profile p) {
        return TABS.computeIfAbsent(p.name, k -> new CopyOnWriteArrayList<>());
    }

    // ------------------------------------------------------------------ lifecycle

    public static void start(Context ctx, Prefs p, Profile profile, int durationMin) throws Exception {
        synchronized (LOCK) {
            if (profile.running) throw new Exception("profile already running");
            appContext = ctx.getApplicationContext();
            prefs = p;
            restoreCookies(profile);
            profile.running = true;
            profile.startedAtMs = System.currentTimeMillis();
            long dur = durationMin > 0 ? durationMin * 60_000L : 0;
            profile.durationMs = dur;
            profile.autoStopAtMs = dur > 0 ? profile.startedAtMs + dur : 0;
            if (dur > 0) {
                Runnable timer = () -> {
                    if (profile.running) {
                        log("[" + profile.name + "] duration expired, auto-stopping");
                        try {
                            stop(profile);
                        } catch (Exception ignored) {
                        }
                    }
                };
                TIMERS.put(profile.name, timer);
                MAIN.postDelayed(timer, dur);
            }
            log("[" + profile.name + "] started" + (dur > 0 ? " (auto-stop in " + durationMin + "m)" : ""));
        }
    }

    public static void stop(Profile profile) {
        synchronized (LOCK) {
            if (!profile.running) return;
            Runnable timer = TIMERS.remove(profile.name);
            if (timer != null) MAIN.removeCallbacks(timer);
            snapshotCookies(profile);
            List<TabItem> tabs = tabsOf(profile);
            runAndWait(() -> {
                for (TabItem t : tabs) {
                    try {
                        t.webView.stopLoading();
                        t.webView.loadUrl("about:blank");
                        t.webView.removeAllViews();
                        t.webView.destroy();
                    } catch (Exception ignored) {
                    }
                }
            });
            tabs.clear();
            profile.running = false;
            profile.autoStopAtMs = 0;
            log("[" + profile.name + "] stopped");
        }
    }

    public static void stopAll() {
        for (Profile p : PROFILES) {
            try {
                stop(p);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ tabs

    @SuppressLint("SetJavaScriptEnabled")
    public static TabItem openTab(Profile profile, String url) throws Exception {
        if (!profile.running) throw new Exception("profile not running (POST /api/profiles/" + profile.name + "/start first)");
        String finalId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<TabItem> tabs = tabsOf(profile);
        TabItem[] holder = new TabItem[1];
        Set<String> visited = VISITED.computeIfAbsent(profile.name, k -> ConcurrentHashMap.newKeySet());
        runAndWait(() -> {
            try {
                TabItem t = new TabItem(finalId, profile, url);
                WebView wv = new WebView(appContext);
                WebSettings st = wv.getSettings();
                st.setJavaScriptEnabled(true);
                st.setDomStorageEnabled(true);
                st.setDatabaseEnabled(true);
                st.setLoadWithOverviewMode(true);
                st.setUseWideViewPort(true);
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(wv, true);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        t.title = view.getTitle() == null ? "" : view.getTitle();
                        t.url = u;
                        try {
                            java.net.URI uri = java.net.URI.create(u);
                            String host = uri.getHost();
                            if (host != null) visited.add(host);
                        } catch (Exception ignored) {
                        }
                        log("[" + profile.name + "] tab " + shortId(t.id) + " loaded: " + u);
                    }

                    @Override
                    public void doUpdateVisitedHistory(WebView view, String u, boolean isReload) {
                        t.url = u;
                        try {
                            java.net.URI uri = java.net.URI.create(u);
                            String host = uri.getHost();
                            if (host != null) visited.add(host);
                        } catch (Exception ignored) {
                        }
                    }
                });
                int w = 1080, h = 2340;
                wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
                wv.layout(0, 0, w, h);
                t.webView = wv;
                wv.loadUrl(url);
                tabs.add(t);
                holder[0] = t;
            } catch (Exception e) {
                log("[" + profile.name + "] openTab failed: " + e.getMessage());
            }
        });
        if (holder[0] == null) throw new Exception("failed to create tab");
        return holder[0];
    }

    public static boolean closeTab(Profile profile, String id) {
        List<TabItem> tabs = tabsOf(profile);
        TabItem target = null;
        for (TabItem t : tabs) if (t.id.equals(id) || t.id.startsWith(id)) target = t;
        if (target == null) return false;
        TabItem f = target;
        runAndWait(() -> {
            try {
                f.webView.stopLoading();
                f.webView.loadUrl("about:blank");
                f.webView.destroy();
            } catch (Exception ignored) {
            }
        });
        tabs.remove(f);
        log("[" + profile.name + "] tab " + shortId(id) + " closed");
        return true;
    }

    public static List<TabItem> tabs(Profile profile) {
        return new ArrayList<>(tabsOf(profile));
    }

    public static TabItem findTab(Profile profile, String id) {
        for (TabItem t : tabsOf(profile)) if (t.id.equals(id) || t.id.startsWith(id)) return t;
        return null;
    }

    public static byte[] screenshot(TabItem t) throws Exception {
        if (t.webView == null) throw new Exception("tab has no view");
        ByteArrayOutputStream[] holder = new ByteArrayOutputStream[1];
        runAndWait(() -> {
            try {
                Bitmap bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                t.webView.draw(c);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                holder[0] = bos;
            } catch (Exception e) {
                log("screenshot failed: " + e.getMessage());
            }
        });
        if (holder[0] == null) throw new Exception("screenshot failed");
        return holder[0].toByteArray();
    }

    public static String eval(TabItem t, String js) throws Exception {
        if (t.webView == null) throw new Exception("tab has no view");
        String[] holder = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                t.webView.evaluateJavascript(js, value -> {
                    holder[0] = value;
                    latch.countDown();
                });
            } catch (Exception e) {
                holder[0] = null;
                latch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
        latch.await(15, TimeUnit.SECONDS);
        if (holder[0] == null) throw new Exception("eval timed out or failed");
        return holder[0];
    }

    // ------------------------------------------------------------------ cookies

    private static void restoreCookies(Profile p) {
        if (prefs == null) return;
        try {
            JSONObject store = prefs.cookieStore();
            JSONArray entries = store.optJSONArray(p.name);
            if (entries == null) return;
            CookieManager cm = CookieManager.getInstance();
            int n = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                String domain = e.getString("domain");
                String cookie = e.optString("cookie", "");
                for (String pair : cookie.split(";")) {
                    pair = pair.trim();
                    if (!pair.isEmpty()) {
                        cm.setCookie("https://" + domain, pair);
                        n++;
                    }
                }
            }
            cm.flush();
            if (n > 0) log("[" + p.name + "] restored " + n + " cookies");
        } catch (Exception e) {
            log("cookie restore failed: " + e.getMessage());
        }
    }

    private static void snapshotCookies(Profile p) {
        if (prefs == null) return;
        try {
            Set<String> visited = VISITED.get(p.name);
            if (visited == null || visited.isEmpty()) return;
            CookieManager cm = CookieManager.getInstance();
            JSONArray entries = new JSONArray();
            for (String domain : visited) {
                String cookie = cm.getCookie("https://" + domain);
                if (cookie != null && !cookie.isEmpty()) {
                    JSONObject e = new JSONObject();
                    e.put("domain", domain);
                    e.put("cookie", cookie);
                    entries.put(e);
                }
            }
            if (entries.length() > 0) {
                JSONObject store = prefs.cookieStore();
                store.put(p.name, entries);
                prefs.saveCookieStore(store);
                log("[" + p.name + "] saved cookies for " + entries.length() + " domains");
            }
        } catch (Exception e) {
            log("cookie snapshot failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ devtools (best-effort)

    /**
     * Try to reach this app's own WebView devtools socket (abstract unix socket)
     * and fetch the real CDP target list. Returns raw JSON or null.
     */
    public static String devtoolsList() {
        String pkg = appContext != null ? appContext.getPackageName() : "cc.samaidev.samcdp";
        String[] candidates = {pkg + "_devtools_remote", pkg, "chrome_devtools_remote"};
        for (String name : candidates) {
            try {
                android.net.LocalSocket sock = new android.net.LocalSocket();
                sock.connect(new android.net.LocalSocketAddress(name,
                        android.net.LocalSocketAddress.Namespace.ABSTRACT));
                sock.setSoTimeout(800);
                java.io.OutputStream os = sock.getOutputStream();
                os.write(("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                java.io.InputStream is = sock.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int r;
                while ((r = is.read(chunk)) != -1) buf.write(chunk, 0, r);
                sock.close();
                String raw = buf.toString("UTF-8");
                int body = raw.indexOf("\r\n\r\n");
                if (body < 0) continue;
                String head = raw.substring(0, body).toLowerCase();
                String bodyStr = raw.substring(body + 4);
                if (head.contains("transfer-encoding: chunked")) {
                    bodyStr = dechunk(bodyStr);
                }
                if (bodyStr.trim().startsWith("[")) return bodyStr;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String dechunk(String s) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.StringReader(s));
            StringBuilder out = new StringBuilder();
            String lenLine;
            while ((lenLine = br.readLine()) != null) {
                lenLine = lenLine.trim();
                if (lenLine.isEmpty()) continue;
                int len = Integer.parseInt(lenLine.split(";")[0], 16);
                if (len == 0) break;
                char[] cbuf = new char[len];
                int off = 0;
                while (off < len) {
                    int r = br.read(cbuf, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
                out.append(cbuf);
                br.readLine(); // trailing CRLF
            }
            return out.toString();
        } catch (Exception e) {
            return s;
        }
    }

    public static String shortId(String id) {
        return id == null ? "" : (id.length() > 6 ? id.substring(0, 6) : id);
    }

    public static String uptimeString(long sinceMs) {
        long s = Math.max(0, (System.currentTimeMillis() - sinceMs) / 1000);
        return s / 3600 + "h" + (s % 3600) / 60 + "m" + s % 60 + "s";
    }
}
