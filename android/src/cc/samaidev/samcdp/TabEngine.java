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
import android.webkit.WebResourceRequest;

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

    public static final int TAB_W = 1920, TAB_H = 1080;

    // Stealth JS injected on every page load to mask Android WebView fingerprint
    static final String STEALTH_JS = "(function(){"
        + "try{"
        // Override navigator.platform to match Mac UA
        + "Object.defineProperty(navigator,'platform',{get:function(){return 'MacIntel';},configurable:true});"
        // Override navigator.userAgent to match Mac UA (double insurance)
        + "Object.defineProperty(navigator,'userAgent',{get:function(){return 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';},configurable:true});"
        // Override navigator.appVersion
        + "Object.defineProperty(navigator,'appVersion',{get:function(){return '5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';},configurable:true});"
        // Override navigator.oscpu
        + "Object.defineProperty(navigator,'oscpu',{get:function(){return 'Intel Mac OS X 10.15';},configurable:true});"
        // Remove touch indicators
        + "Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;},configurable:true});"
        + "try{Object.defineProperty(navigator,'TouchEvent',{get:function(){return undefined;},configurable:true});}catch(e){}"
        + "try{Object.defineProperty(navigator,'ontouchstart',{get:function(){return null;},configurable:true});}catch(e){}"
        // Override screen dimensions to desktop
        + "try{Object.defineProperty(screen,'width',{get:function(){return 1920;},configurable:true});"
        + "Object.defineProperty(screen,'height',{get:function(){return 1080;},configurable:true});"
        + "Object.defineProperty(screen,'availWidth',{get:function(){return 1920;},configurable:true});"
        + "Object.defineProperty(screen,'availHeight',{get:function(){return 1050;},configurable:true});"
        + "Object.defineProperty(screen,'colorDepth',{get:function(){return 24;},configurable:true});"
        + "Object.defineProperty(screen,'pixelDepth',{get:function(){return 24;},configurable:true});}catch(e){}"
        // Override window dimensions
        + "try{Object.defineProperty(window,'innerWidth',{get:function(){return 1920;},configurable:true});"
        + "Object.defineProperty(window,'innerHeight',{get:function(){return 937;},configurable:true});"
        + "Object.defineProperty(window,'outerWidth',{get:function(){return 1920;},configurable:true});"
        + "Object.defineProperty(window,'outerHeight',{get:function(){return 1080;},configurable:true});}catch(e){}"
        // Override devicePixelRatio
        + "try{Object.defineProperty(window,'devicePixelRatio',{get:function(){return 1;},configurable:true});}catch(e){}"
        // Mask WebGL vendor/renderer
        + "try{var gp=WebGLRenderingContext.prototype.getParameter;WebGLRenderingContext.prototype.getParameter=function(p){if(p===37445)return 'Intel Inc.';if(p===37446)return 'Intel Iris OpenGL Engine';return gp.call(this,p);};}catch(e){}"
        + "try{var gp2=WebGL2RenderingContext.prototype.getParameter;WebGL2RenderingContext.prototype.getParameter=function(p){if(p===37445)return 'Intel Inc.';if(p===37446)return 'Intel Iris OpenGL Engine';return gp2.call(this,p);};}catch(e){}"
        // Override navigator.languages
        + "try{Object.defineProperty(navigator,'languages',{get:function(){return ['en-US','en'];},configurable:true});}catch(e){}"
        // Override navigator.hardwareConcurrency
        + "try{Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 8;},configurable:true});}catch(e){}"
        // Override navigator.deviceMemory
        + "try{Object.defineProperty(navigator,'deviceMemory',{get:function(){return 8;},configurable:true});}catch(e){}"
        // Hide webdriver
        + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}"
        // Override navigator.connection (remove mobile hints)
        + "try{Object.defineProperty(navigator,'connection',{get:function(){return undefined;},configurable:true});}catch(e){}"
        // Override navigator.permissions API to silence notifications query
        + "try{var origQuery=navigator.permissions.query;navigator.permissions.query=function(p){if(p&&p.name==='notifications')return Promise.resolve({state:'prompt'});return origQuery.call(this,p);};}catch(e){}"
        // Patch canvas fingerprint (add subtle noise)
        + "try{var origToDataURL=HTMLCanvasElement.prototype.toDataURL;HTMLCanvasElement.prototype.toDataURL=function(){var r=origToDataURL.apply(this,arguments);if(r.length>100){var noise=Math.floor(Math.random()*10);r=r.substring(0,r.length-3)+noise.toString().padStart(3,'0');}return r;};}catch(e){}"
        // Override navigator.plugins to look like desktop Chrome
        + "try{Object.defineProperty(navigator,'plugins',{get:function(){return [{name:'PDF Viewer'},{name:'Chrome PDF Viewer'},{name:'Chromium PDF Viewer'}];},configurable:true});}catch(e){}"
        // Override navigator.mimeTypes
        + "try{Object.defineProperty(navigator,'mimeTypes',{get:function(){return [{type:'application/pdf'},{type:'text/pdf'}];},configurable:true});}catch(e){}"
        + "}catch(e){}"
        + "})();";



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

                    // --- stealth hardening ---
                    st.setSupportZoom(false);
                    st.setBuiltInZoomControls(false);
                    st.setDisplayZoomControls(false);
                    st.setMediaPlaybackRequiresUserGesture(false);
                    st.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    try { android.webkit.WebSettings.class.getMethod("setDisabledActionModeMenuItems", int.class).invoke(st, 1); } catch(Exception ignored) {}

                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setAcceptThirdPartyCookies(wv, true);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String u, android.graphics.Bitmap favicon) {
                            // Inject stealth JS BEFORE page scripts run
                            view.evaluateJavascript(STEALTH_JS, null);
                        }

                        @Override
                        public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                            // Pass through normally — no interception needed
                            return null;
                        }

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
            JSONObject o = tabJson(holder[0]);
            o.put("ok", true);
            JSONObject wrap = new JSONObject();
            wrap.put("ok", true);
            wrap.put("tab", o);
            return wrap.toString();
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
        android.util.Log.i("samcdp", line);
        ProfileManager.dispatchLog(line);
    }
}
