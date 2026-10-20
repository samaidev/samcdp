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
import android.view.ViewGroup;

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

    // Portrait phone CSS viewport (Pixel 7) — width < height, matches the mobile UA below
    public static final int TAB_W = 412, TAB_H = 915;

    // Headless PHYSICAL pixel size = CSS viewport * real device density.
    // A detached WebView still inherits the device density (e.g. 2.75 on a 2K phone),
    // so measuring 412x915 raw would give a 150px CSS viewport and wreck layout.
    private static volatile int headlessW = TAB_W, headlessH = TAB_H;

    // Chrome-on-Android mobile UA (Pixel 7 / Android 13 / Chrome 126).
    // Self-consistent with the real device: the APK runs on an Android phone, so the
    // mask only strips WebView markers ('; wv', 'Build/') and stays in the same family.
    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    // Stealth JS injected on every page load — MOBILE portrait fingerprint.
    // Everything below mirrors what a real Pixel 7 running Chrome reports, so the
    // mask survives cross-checks: real GPU / touch stack stay untouched (already
    // mobile), only WebView-vs-Chrome differences are patched.
    static final String STEALTH_JS = "(function(){"
        + "try{"
        // Override navigator.platform (Chrome on Android reports 'Linux armv81')
        + "Object.defineProperty(navigator,'platform',{get:function(){return 'Linux armv81';},configurable:true});"
        // Override navigator.userAgent — Chrome mobile UA, strips the WebView '; wv' marker
        + "Object.defineProperty(navigator,'userAgent',{get:function(){return '" + MOBILE_UA + "';},configurable:true});"
        // Override navigator.appVersion
        + "Object.defineProperty(navigator,'appVersion',{get:function(){return '5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36';},configurable:true});"
        // Touch screen: 5 touch points (real phone). Do NOT hide TouchEvent/ontouchstart —
        // a mobile UA with no touch support is an instant red flag.
        + "Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 5;},configurable:true});"
        // Override screen dimensions to PORTRAIT phone CSS viewport (width < height)
        + "try{Object.defineProperty(screen,'width',{get:function(){return 412;},configurable:true});"
        + "Object.defineProperty(screen,'height',{get:function(){return 915;},configurable:true});"
        + "Object.defineProperty(screen,'availWidth',{get:function(){return 412;},configurable:true});"
        + "Object.defineProperty(screen,'availHeight',{get:function(){return 915;},configurable:true});"
        + "Object.defineProperty(screen,'colorDepth',{get:function(){return 24;},configurable:true});"
        + "Object.defineProperty(screen,'pixelDepth',{get:function(){return 24;},configurable:true});}catch(e){}"
        // Override window dimensions (portrait, no browser chrome in headless WebView)
        + "try{Object.defineProperty(window,'innerWidth',{get:function(){return 412;},configurable:true});"
        + "Object.defineProperty(window,'innerHeight',{get:function(){return 915;},configurable:true});"
        + "Object.defineProperty(window,'outerWidth',{get:function(){return 412;},configurable:true});"
        + "Object.defineProperty(window,'outerHeight',{get:function(){return 915;},configurable:true});}catch(e){}"
        // Override devicePixelRatio (Pixel 7 = 2.625)
        + "try{Object.defineProperty(window,'devicePixelRatio',{get:function(){return 2.625;},configurable:true});}catch(e){}"
        // WebGL vendor/renderer left UNTOUCHED — the real mobile GPU (Adreno/Mali)
        // matches the Android UA; faking 'Intel Iris OpenGL Engine' contradicts it.
        // navigator.connection left UNTOUCHED — mobile Chrome exposes it; hiding it
        // looks like a desktop spoof.
        // Override navigator.languages
        + "try{Object.defineProperty(navigator,'languages',{get:function(){return ['en-US','en'];},configurable:true});}catch(e){}"
        // Override navigator.hardwareConcurrency (Pixel 7 Tensor G2 = 8 cores)
        + "try{Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 8;},configurable:true});}catch(e){}"
        // Override navigator.deviceMemory
        + "try{Object.defineProperty(navigator,'deviceMemory',{get:function(){return 8;},configurable:true});}catch(e){}"
        // Hide webdriver
        + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}"
        // Override navigator.permissions API to silence notifications query
        + "try{var origQuery=navigator.permissions.query;navigator.permissions.query=function(p){if(p&&p.name==='notifications')return Promise.resolve({state:'prompt'});return origQuery.call(this,p);};}catch(e){}"
        // Patch canvas fingerprint (add subtle noise)
        + "try{var origToDataURL=HTMLCanvasElement.prototype.toDataURL;HTMLCanvasElement.prototype.toDataURL=function(){var r=origToDataURL.apply(this,arguments);if(r.length>100){var noise=Math.floor(Math.random()*10);r=r.substring(0,r.length-3)+noise.toString().padStart(3,'0');}return r;};}catch(e){}"
        // Override navigator.plugins — Chrome (incl. Android) exposes the same 5 PDF
        // entries; real WebView returns an EMPTY list which is a WebView tell-tale
        + "try{Object.defineProperty(navigator,'plugins',{get:function(){var arr=[{name:'PDF Viewer'},{name:'Chrome PDF Viewer'},{name:'Chromium PDF Viewer'},{name:'Microsoft Edge PDF Viewer'},{name:'Web Browser PDF Viewer'}];arr.item=function(i){return arr[i]||null;};arr.namedItem=function(n){for(var i=0;i<arr.length;i++)if(arr[i].name===n)return arr[i];return null;};return arr;},configurable:true});}catch(e){}"
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

    // ------------------------------------------------------------ visibility (human handover)

    private static volatile android.app.Activity visibleHost;
    private static volatile ViewGroup visibleRoot;
    private static volatile String visibleTabId;

    /** Current profile name of THIS runner process (null = engine down). */
    static String profileName() { return profileName; }

    /** Tab id currently attached to the visible host (null = headless). */
    static String visibleTabId() { return visibleTabId; }

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
        // close any full-screen host BEFORE destroying the WebViews underneath it
        detach();
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
            // recompute headless physical size from the REAL device density every open —
            // CSS viewport then equals TAB_W x TAB_H exactly, in sync with STEALTH_JS
            try {
                float dpr = ctx.getResources().getDisplayMetrics().density;
                headlessW = Math.round(TAB_W * dpr);
                headlessH = Math.round(TAB_H * dpr);
            } catch (Exception ignored) {
            }
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
                        st.setUserAgentString(MOBILE_UA);
                    }

                    // --- stealth hardening (phone behaviour: pinch-zoom, no buttons) ---
                    st.setSupportZoom(true);
                    st.setBuiltInZoomControls(true);
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
                    wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(headlessW, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(headlessH, android.view.View.MeasureSpec.EXACTLY));
                    wv.layout(0, 0, headlessW, headlessH);
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
                int w = tab.webView.getWidth(), h = tab.webView.getHeight();
                if (w <= 0 || h <= 0) {
                    w = headlessW;
                    h = headlessH;
                }
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
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

    // ------------------------------------------------------------ visibility (human handover)

    /**
     * Open (or re-target) the full-screen TabActivity that shows a tab's WebView
     * to a human — for CAPTCHA / login / 2FA / consent handover.
     * Called from the runner's local tab API: POST /show?tab=<id>.
     */
    static String showTab(Context ctx, String tabId) {
        try {
            List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
            if (tabs == null || tabs.isEmpty()) return err("no tabs open in this profile");
            TabItem target = null;
            if (tabId != null && !tabId.isEmpty()) {
                for (TabItem t : tabs) if (t.id.equals(tabId) || t.id.startsWith(tabId)) target = t;
                if (target == null) return err("tab not found: " + tabId);
            } else {
                target = tabs.get(0);
                for (TabItem t : tabs) if (t.openedAtMs > target.openedAtMs) target = t;
            }
            if (target.webView == null) return err("tab has no webview yet");
            String tid = target.id;

            // already visible with the same tab? nothing to do
            if (visibleHost != null && tid.equals(visibleTabId)) {
                return new JSONObject().put("ok", true).put("visible", true)
                        .put("tab", tid).put("profile", profileName).toString();
            }

            Class<?> svc = BaseRunnerService.activeRunnerClass();
            if (svc == null) return err("runner service class unknown");
            String actCls = svc.getName().replace("RunnerService", "TabActivity");
            android.content.Intent i = new android.content.Intent(ctx, Class.forName(actCls));
            i.putExtra("tab", tid);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("visible", true);
            o.put("tab", tid);
            o.put("profile", profileName);
            return o.toString();
        } catch (Exception e) {
            return err("show failed: " + e.getMessage());
        }
    }

    /**
     * TabActivity.onCreate/onNewIntent -> attach the requested (or newest) tab's
     * WebView to the activity's full-screen root. Returns null on success, else an
     * error hint. MUST run on the runner process main thread (Activity lifecycle
     * guarantees it). Idempotent: re-attaching the tab that is already on this root
     * is a no-op (survives singleTask onNewIntent re-delivery).
     */
    static String attachRequested(android.app.Activity host, ViewGroup root, String want) {
        List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
        if (tabs == null || tabs.isEmpty()) return "no tabs";
        TabItem target = null;
        if (want != null && !want.isEmpty()) {
            for (TabItem t : tabs) if (t.id.equals(want) || t.id.startsWith(want)) target = t;
        }
        if (target == null) {
            // onNewIntent without explicit tab: keep whatever is currently attached
            if (host == visibleHost && visibleTabId != null) return null;
            target = tabs.get(0);
            for (TabItem t : tabs) if (t.openedAtMs > target.openedAtMs) target = t;
        }
        if (target.webView == null) return "tab has no webview";
        final TabItem t = target;

        // same tab already on this root? nothing to do
        if (host == visibleHost && t.id.equals(visibleTabId) && t.webView.getParent() == root) {
            return null;
        }

        // if another tab's WebView is still attached somewhere, free it first
        detachAttached();
        attachToRoot(t, root);
        visibleHost = host;
        visibleTabId = t.id;
        log("[" + profileName + "] webview visible (human handover), tab " + t.id);
        return null;
    }

    /** Attach a tab's WebView to a root (fullscreen) and resume it. Main thread only. */
    private static void attachToRoot(TabItem t, ViewGroup root) {
        try {
            ViewGroup parent = (ViewGroup) t.webView.getParent();
            if (parent != null) parent.removeView(t.webView);
            root.addView(t.webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.webView.onResume();
        } catch (Exception e) {
            log("attach failed: " + e.getMessage());
        }
    }

    /**
     * Frontend tab switching WITHOUT restarting the host activity:
     * detach the currently visible WebView (back to headless) and attach the
     * requested one into the SAME root. If nothing is visible yet, fall back
     * to showTab() which launches the profile's TabActivity.
     */
    static synchronized String switchTab(Context ctx, String tabId) {
        try {
            List<TabItem> tabs = profileName == null ? null : TABS.get(profileName);
            if (tabs == null || tabs.isEmpty()) return err("no tabs open in this profile");
            TabItem target = null;
            if (tabId != null && !tabId.isEmpty()) {
                for (TabItem t : tabs) if (t.id.equals(tabId) || t.id.startsWith(tabId)) target = t;
                if (target == null) return err("tab not found: " + tabId);
            } else {
                target = tabs.get(0);
                for (TabItem t : tabs) if (t.openedAtMs > target.openedAtMs) target = t;
            }
            if (target.webView == null) return err("tab has no webview yet");
            String tid = target.id;

            android.app.Activity host = visibleHost;
            ViewGroup root = visibleRoot;
            if (host == null || root == null || tid.equals(visibleTabId)) {
                // nothing visible (or already showing this tab) -> route through show
                return showTab(ctx, tid);
            }
            final TabItem t = target;
            detachAttached();
            final ViewGroup rootF = root;
            Runnable attach = () -> {
                attachToRoot(t, rootF);
                visibleHost = host;
                visibleRoot = rootF;
                visibleTabId = t.id;
            };
            if (Looper.myLooper() == Looper.getMainLooper()) attach.run();
            else MAIN.post(attach);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("visible", true);
            o.put("tab", tid);
            o.put("profile", profileName);
            o.put("switched", true);
            log("[" + profileName + "] switched visible webview -> tab " + tid);
            return o.toString();
        } catch (Exception e) {
            return err("switch failed: " + e.getMessage());
        }
    }

    /** Close the visible host (if any) and put the WebView back to headless mode. */
    static String hide() {
        android.app.Activity host = visibleHost;
        if (host == null) {
            return "{\"ok\":true,\"visible\":false}";
        }
        try {
            host.finish();
        } catch (Exception ignored) {
        }
        return "{\"ok\":true,\"visible\":false}";
    }

    /** Activity.onDestroy -> full detach: finish-safe, idempotent. */
    static void detach() {
        android.app.Activity host = visibleHost;
        visibleHost = null;
        visibleRoot = null;
        if (host != null && !host.isFinishing()) {
            try {
                host.finish();
            } catch (Exception ignored) {
            }
        }
        detachAttached();
    }

    /** Remove the attached WebView (if any) and restore headless 412x915 layout. */
    private static void detachAttached() {
        String tid = visibleTabId;
        visibleTabId = null;
        visibleRoot = null;
        if (tid == null || profileName == null) return;
        List<TabItem> tabs = TABS.get(profileName);
        if (tabs == null) return;
        for (TabItem t : tabs) {
            if (t.id.equals(tid) && t.webView != null) {
                final WebView wv = t.webView;
                runAndWait(() -> {
                    try {
                        ViewGroup parent = (ViewGroup) wv.getParent();
                        if (parent != null) parent.removeView(wv);
                        wv.onPause();
                        wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(headlessW, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(headlessH, android.view.View.MeasureSpec.EXACTLY));
                        wv.layout(0, 0, headlessW, headlessH);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    static String visibleJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            boolean vis = visibleHost != null && visibleTabId != null;
            o.put("visible", vis);
            o.put("tab", visibleTabId == null ? JSONObject.NULL : visibleTabId);
            if (profileName != null) o.put("profile", profileName);
            if (vis) {
                List<TabItem> tabs = TABS.get(profileName);
                if (tabs != null) {
                    for (TabItem t : tabs) {
                        if (t.id.equals(visibleTabId)) {
                            o.put("url", t.url == null ? JSONObject.NULL : t.url);
                            o.put("title", t.title == null ? "" : t.title);
                            break;
                        }
                    }
                }
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
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
