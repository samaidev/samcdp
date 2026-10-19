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
 * Per-profile WebView engine, hosted INSIDE a dedicated runner process.
 * Because each runner process has its own WebView data directory
 * (WebView.setDataDirectorySuffix) and its own CookieManager instance,
 * profiles get TRUE cookie isolation — a real multi-cookie-jar pool.
 * Exposes a tiny localhost-only tab API consumed by the main process.
 */
public class TabEngine {

    public static final int TAB_W = 1080, TAB_H = 2340;

    private static final Map<String, List<TabItem>> TABS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> VISITED = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String profileName;
    private static int slot;
    private static long autoStopAtMs;
    private static Runnable durationTimer;

    static class TabItem {
        final String id;
        volatile String url;
        volatile String title = "";
        volatile long openedAtMs;
        WebView webView; // main thread of the runner process only

        TabItem(String id, String url) {
            this.id = id;
            this.url = url;
            this.openedAtMs = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ lifecycle

    static void start(Context ctx, String name, int durationMin) {
        profileName = name;
        slot = 0;
        log("[" + name + "] engine starting (isolated cookie jar)");
        restoreCookies(ctx);
        if (durationMin > 0) {
            autoStopAtMs = System.currentTimeMillis() + durationMin * 60_000L;
            durationTimer = () -> {
                log("[" + profileName + "] duration expired, auto-stopping");
                BaseRunnerService.stopFromInside(ctx);
            };
            MAIN.postDelayed(durationTimer, durationMin * 60_000L);
        } else {
            autoStopAtMs = 0;
        }
    }

    static void stop(Context ctx) {
        if (durationTimer != null) {
            MAIN.removeCallbacks(durationTimer);
            durationTimer = null;
        }
        for (List<TabItem> tabs : TABS.values()) {
            snapshotCookies(ctx);
            runAndWait(() -> {
                for (TabItem t : tabs) {
                    try {
                        t.webView.stopLoading();
                        t.webView.loadUrl("about:blank");
                        t.webView.destroy();
                    } catch (Exception ignored) {
                    }
                }
            });
            tabs.clear();
        }
        log("[" + profileName + "] engine stopped (cookies persisted)");
    }

    static String infoJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("profile", profileName);
            o.put("tabs", TABS.isEmpty() ? 0 : TABS.values().iterator().next().size());
            o.put("auto_stop_in_s", autoStopAtMs > 0
                    ? Math.max(0, (autoStopAtMs - System.currentTimeMillis()) / 1000) : 0);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    // ------------------------------------------------------------ tab API

    static synchronized String openTab(Context ctx, String url, String ua) {
        try {
            if (profileName == null) return err("engine not started");
            List<TabItem> tabs = TABS.computeIfAbsent(profileName, k -> new CopyOnWriteArrayList<>());
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Set<String> visited = VISITED.computeIfAbsent(profileName, k -> ConcurrentHashMap.newKeySet());
            TabItem[] holder = new TabItem[1];
            runAndWait(() -> {
                try {
                    TabItem t = new TabItem(id, url);
                    WebView wv = new WebView(ctx);
                    WebSettings st = wv.getSettings();
                    st.setJavaScriptEnabled(true);
                    st.setDomStorageEnabled(true);
                    st.setDatabaseEnabled(true);
                    st.setLoadWithOverviewMode(true);
                    st.setUseWideViewPort(true);
                    if (ua != null && !ua.isEmpty()) {
                        st.setUserAgentString(ua);
                    } else {
                        st.setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                    }
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setAcceptThirdPartyCookies(wv, true);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, String u) {
                            return u != null && !u.startsWith("http://") && !u.startsWith("https://")
                                    && !u.startsWith("file://") && !u.startsWith("about:")
                                    && !u.startsWith("data:");
                        }

                        @Override
                        public void onPageFinished(WebView view, String u) {
                            t.title = view.getTitle() == null ? "" : view.getTitle();
                            t.url = u;
                            track(visited, u);
                        }

                        @Override
                        public void doUpdateVisitedHistory(WebView view, String u, boolean reload) {
                            t.url = u;
                            track(visited, u);
                        }
                    });
                    wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(TAB_W, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(TAB_H, android.view.View.MeasureSpec.EXACTLY));
                    wv.layout(0, 0, TAB_W, TAB_H);
                    t.webView = wv;
                    wv.loadUrl(url);
                    tabs.add(t);
                    holder[0] = t;
                } catch (Exception e) {
                    log("[" + profileName + "] openTab failed: " + e.getMessage());
                }
            });
            if (holder[0] == null) return err("failed to create tab");
            return tabJson(holder[0]).put("ok", true).toString();
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    static String listTabs() {
        try {
            JSONArray arr = new JSONArray();
            List<TabItem> tabs = profileName == null ? new ArrayList<>() : TABS.get(profileName);
            if (tabs != null) for (TabItem t : tabs) arr.put(tabJson(t));
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("tabs", arr);
            return o.toString();
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    static synchronized String closeTab(String id) {
        List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
        if (tabs == null) return err("no tabs");
        TabItem target = null;
        for (TabItem t : tabs) if (t.id.equals(id) || t.id.startsWith(id)) target = t;
        if (target == null) return err("tab not found: " + id);
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
        return "{\"ok\":true}";
    }

    static String eval(String id, String js) {
        List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
        TabItem t = null;
        if (tabs != null) for (TabItem x : tabs) if (x.id.equals(id) || x.id.startsWith(id)) t = x;
        if (t == null || t.webView == null) return err("tab not found: " + id);
        final TabItem tab = t;
        String[] holder = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                tab.webView.evaluateJavascript(js, v -> {
                    holder[0] = v;
                    latch.countDown();
                });
            } catch (Exception e) {
                holder[0] = null;
                latch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (holder[0] == null) return err("eval timed out");
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("result", new org.json.JSONTokener(holder[0]).nextValue());
            return o.toString();
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("result", holder[0]);
            } catch (Exception ignored) {
            }
            return o.toString();
        }
    }

    static byte[] screenshot(String id) {
        List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
        TabItem t = null;
        if (tabs != null) for (TabItem x : tabs) if (x.id.equals(id) || x.id.startsWith(id)) t = x;
        if (t == null || t.webView == null) return null;
        final TabItem tab = t;
        ByteArrayOutputStream[] holder = new ByteArrayOutputStream[1];
        runAndWait(() -> {
            try {
                Bitmap bmp = Bitmap.createBitmap(TAB_W, TAB_H, Bitmap.Config.ARGB_8888);
                tab.webView.draw(new Canvas(bmp));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                holder[0] = bos;
            } catch (Exception e) {
                log("screenshot failed: " + e.getMessage());
            }
        });
        return holder[0] == null ? null : holder[0].toByteArray();
    }

    // ------------------------------------------------------------ cookies (per-process jar = true isolation)

    private static void track(Set<String> visited, String u) {
        try {
            String host = java.net.URI.create(u).getHost();
            if (host != null) visited.add(host);
        } catch (Exception ignored) {
        }
    }

    private static void restoreCookies(Context ctx) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("cookies_" + profileName, Context.MODE_PRIVATE);
            String raw = sp.getString("store", null);
            if (raw == null) return;
            JSONArray entries = new JSONArray(raw);
            CookieManager cm = CookieManager.getInstance();
            int n = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                String domain = e.getString("domain");
                for (String pair : e.optString("cookie", "").split(";")) {
                    pair = pair.trim();
                    if (!pair.isEmpty()) {
                        cm.setCookie("https://" + domain, pair);
                        n++;
                    }
                }
            }
            cm.flush();
            if (n > 0) log("[" + profileName + "] restored " + n + " cookies (own jar)");
        } catch (Exception e) {
            log("cookie restore failed: " + e.getMessage());
        }
    }

    private static void snapshotCookies(Context ctx) {
        try {
            Set<String> visited = VISITED.get(profileName);
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
                android.content.SharedPreferences sp =
                        ctx.getSharedPreferences("cookies_" + profileName, Context.MODE_PRIVATE);
                sp.edit().putString("store", entries.toString()).apply();
                cm.flush();
                log("[" + profileName + "] saved cookies for " + entries.length() + " domains (own jar)");
            }
        } catch (Exception e) {
            log("cookie snapshot failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ helpers

    private static JSONObject tabJson(TabItem t) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("title", t.title);
            o.put("url", t.url);
            o.put("type", "page");
            o.put("profile", profileName);
            o.put("opened_at_s", t.openedAtMs / 1000);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", String.valueOf(msg)).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

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

    static void log(String line) {
        android.util.Log.i("SamCDP", line);
        ProfileManager.dispatchLog(line);
    }
}
