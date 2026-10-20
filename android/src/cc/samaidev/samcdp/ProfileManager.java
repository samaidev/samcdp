package cc.samaidev.samcdp;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main-process profile registry. Each STARTED profile lives in its own
 * runner process (RunnerService1..6, :cdp1..:cdp6) with an isolated cookie
 * jar; tab operations are proxied to the runner's localhost tab API.
 */
public class ProfileManager {

    public interface LogSink {
        void onLog(String line);
    }

    public static final int MAX_PROFILES = 6;

    private static final List<Profile> PROFILES = new CopyOnWriteArrayList<>();
    private static volatile LogSink logSink;
    private static volatile Prefs prefs;

    public static void setLogSink(LogSink sink) {
        logSink = sink;
    }

    public static void dispatchLog(String line) {
        String stamped = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                + " " + line;
        android.util.Log.i("samcdp", line);
        LogSink s = logSink;
        if (s != null) s.onLog(stamped);
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

    public static void saveProfiles() {
        if (prefs == null) return;
        JSONArray arr = new JSONArray();
        for (Profile p : PROFILES) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("port", p.port);
                o.put("enabled", p.enabled);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.saveProfiles(arr);
    }

    // ------------------------------------------------------------ runner slot allocation

    private static int freeSlot() {
        boolean[] used = new boolean[MAX_PROFILES + 1];
        for (Profile p : PROFILES) if (p.slot > 0) used[p.slot] = true;
        for (int i = 1; i <= MAX_PROFILES; i++) if (!used[i]) return i;
        return -1;
    }

    private static Class<?> runnerClass(int slot) {
        switch (slot) {
            case 2: return RunnerService2.class;
            case 3: return RunnerService3.class;
            case 4: return RunnerService4.class;
            case 5: return RunnerService5.class;
            case 6: return RunnerService6.class;
            default: return RunnerService1.class;
        }
    }

    public static void start(Context ctx, Profile profile, int durationMin) throws Exception {
        if (profile.running) throw new Exception("profile already running");
        int slot = freeSlot();
        if (slot < 0) throw new Exception("all " + MAX_PROFILES + " runner slots busy, stop a profile first");
        profile.slot = slot;
        profile.durationMin = durationMin;
        profile.startedAtMs = System.currentTimeMillis();
        profile.autoStopAtMs = durationMin > 0 ? profile.startedAtMs + durationMin * 60_000L : 0;
        Intent i = new Intent(ctx, runnerClass(slot))
                .setAction(BaseRunnerService.ACTION_START)
                .putExtra("name", profile.name)
                .putExtra("duration_min", durationMin);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
        profile.running = true;
        dispatchLog("[" + profile.name + "] starting in isolated runner #" + slot
                + (durationMin > 0 ? " (auto-stop " + durationMin + "m)" : ""));
    }

    public static void stop(Context ctx, Profile profile) {
        if (profile.slot > 0) {
            Intent i = new Intent(ctx, runnerClass(profile.slot))
                    .setAction(BaseRunnerService.ACTION_STOP);
            try {
                ctx.startService(i);
            } catch (Exception ignored) {
            }
        }
        profile.slot = 0;
        profile.running = false;
        profile.autoStopAtMs = 0;
        dispatchLog("[" + profile.name + "] stopped");
    }

    public static void stopAll(Context ctx) {
        for (Profile p : PROFILES) {
            try {
                stop(ctx, p);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ proxy to runner tab API

    private static String runnerBase(Profile p) {
        return "http://127.0.0.1:" + (9400 + p.slot);
    }

    public static boolean runnerUp(Profile p) {
        if (p.slot <= 0) return false;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(runnerBase(p) + "/info")
                    .openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(2000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static ProxyResult proxy(Profile p, String method, String path, byte[] body) {
        ProxyResult r = new ProxyResult();
        if (p.slot <= 0) {
            r.status = 409;
            r.contentType = "application/json; charset=utf-8";
            r.payload = "{\"ok\":false,\"error\":\"profile not running\"}".getBytes();
            return r;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(runnerBase(p) + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(3000);
            c.setReadTimeout(30000);
            if (body != null && body.length > 0) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }
            r.status = c.getResponseCode();
            r.contentType = c.getContentType() == null ? "application/json; charset=utf-8" : c.getContentType();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) != -1) bos.write(buf, 0, n);
            if (in != null) in.close();
            r.payload = bos.toByteArray();
            c.disconnect();
        } catch (Exception e) {
            r.status = 502;
            r.contentType = "application/json; charset=utf-8";
            r.payload = ("{\"ok\":false,\"error\":\"runner unreachable: " + e.getMessage() + "\"}").getBytes();
        }
        return r;
    }

    public static class ProxyResult {
        public int status;
        public String contentType;
        public byte[] payload;
    }

    public static ProxyResult tabs(Profile p) {
        return proxy(p, "GET", "/tabs", null);
    }

    public static ProxyResult openTab(Profile p, String url, String ua) {
        return proxy(p, "POST", "/open?url=" + urlencode(url) + (ua != null ? "&ua=" + urlencode(ua) : ""), null);
    }

    public static ProxyResult closeTab(Profile p, String id) {
        return proxy(p, "DELETE", "/tabs/" + id, null);
    }

    public static ProxyResult eval(Profile p, String id, String js) {
        try {
            JSONObject b = new JSONObject().put("js", js);
            return proxy(p, "POST", "/tabs/" + id + "/eval", b.toString().getBytes());
        } catch (Exception e) {
            ProxyResult r = new ProxyResult();
            r.status = 400;
            r.contentType = "application/json; charset=utf-8";
            r.payload = "{\"ok\":false,\"error\":\"bad js\"}".getBytes();
            return r;
        }
    }

    public static ProxyResult screenshot(Profile p, String id) {
        return proxy(p, "GET", "/tabs/" + id + "/screenshot", null);
    }

    /** Show a tab's WebView full-screen on the phone for human handover (CAPTCHA/login). */
    public static ProxyResult show(Profile p, String tabId) {
        String q = tabId != null && !tabId.isEmpty() ? "?tab=" + urlencode(tabId) : "";
        return proxy(p, "POST", "/show" + q, null);
    }

    /** Switch the profile's visible WebView to another tab (frontend switcher). */
    public static ProxyResult switchTab(Profile p, String tabId) {
        String q = tabId != null && !tabId.isEmpty() ? "?tab=" + urlencode(tabId) : "";
        return proxy(p, "POST", "/switch" + q, null);
    }

    /** Hide the visible WebView and return the tab to headless mode. */
    public static ProxyResult hide(Profile p) {
        return proxy(p, "POST", "/hide", null);
    }

    /** Visibility state of the profile's WebView. */
    public static ProxyResult visible(Profile p) {
        return proxy(p, "GET", "/visible", null);
    }

    public static String urlencode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
