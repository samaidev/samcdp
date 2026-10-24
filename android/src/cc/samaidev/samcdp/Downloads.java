package cc.samaidev.samcdp;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads — WebView download capture + registry (v1.4.2).
 *
 * Static state is per PROCESS, and each :cdpN process serves exactly one
 * profile, so one instance == one profile's downloads. Files land under
 * <filesDir>/downloads/slot&lt;N&gt;/ (private storage; nothing is written to the
 * phone's public Downloads folder) and the index is persisted as meta.json so
 * lists survive restarts and can be read by other processes (DownloadsProvider,
 * main-process API aggregation).
 *
 * Sources handled from WebView.setDownloadListener:
 *   http(s):// → background streaming download (redirect-following, carries the
 *                tab's cookies + the WebView's User-Agent)
 *   blob:      → chunked JS fetch → base64 → file (modern sites export via blob)
 *   data:      → inline decode
 *
 * API surface (runner 9401-9406, proxied by the main API 9334):
 *   GET    /downloads                list
 *   GET    /downloads/<id>/file      pull raw bytes
 *   DELETE /downloads/<id>           delete one
 *   POST   /downloads/<id>/upload?tab=ID&selector=CSS
 *                                    hand the file to a page's <input type=file>
 */
public final class Downloads {

    public static class Entry {
        public String id;
        public String name;
        public String path;   // absolute file path
        public long size;     // bytes (final when done)
        public long got;      // bytes so far while running
        public String mime;
        public String url;    // source URL
        public long ts;       // finished/failed time
        public String state;  // running | done | error
        public String error;
        public String profile;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("size", state == null || "running".equals(state) ? Math.max(size, got) : size);
                o.put("mime", mime == null ? "" : mime);
                o.put("url", url == null ? "" : url);
                o.put("ts", ts);
                o.put("state", state);
                if (error != null) o.put("error", error);
                if (profile != null) o.put("profile", profile);
                // persisted in meta.json so entries survive restarts; stripped
                // from API-facing listings below (private on-device path)
                o.put("path", path == null ? "" : path);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.name = o.optString("name");
            e.path = o.optString("path");
            e.size = o.optLong("size");
            e.mime = o.optString("mime", "");
            e.url = o.optString("url", "");
            e.ts = o.optLong("ts");
            e.state = o.optString("state", "done");
            e.error = o.has("error") ? o.optString("error") : null;
            e.profile = o.has("profile") ? o.optString("profile") : null;
            return e;
        }
    }

    private static final Object LOCK = new Object();
    private static int slot = 0;
    private static String profileName = "";
    private static File dir;                       // <filesDir>/downloads/slotN
    private static final Map<String, Entry> reg = new HashMap<>(); // id → entry

    /** Content provider authority (also in AndroidManifest). */
    public static final String AUTHORITY = "cc.samaidev.samcdp.downloads";

    private Downloads() {
    }

    /** Call from BaseRunnerService.startTabServer() — once per profile process. */
    public static void init(Context ctx, int slotNum, String profile) {
        synchronized (LOCK) {
            slot = slotNum;
            profileName = profile == null ? ("slot" + slotNum) : profile;
            dir = new File(ctx.getFilesDir(), "downloads/slot" + slotNum);
            if (!dir.exists()) dir.mkdirs();
            loadMeta(ctx);
        }
    }

    /** Root dir holding every slot's meta.json (provider + aggregation use). */
    public static File rootDir(Context ctx) {
        return new File(ctx.getFilesDir(), "downloads");
    }

    // ------------------------------------------------------------ WebView hook

    /** WebView.setDownloadListener target — dispatches by URL scheme. */
    public static void handle(Context ctx, String url, String userAgent,
                              String contentDisposition, String mimetype, long contentLength) {
        if (url == null || url.isEmpty()) return;
        try {
            if (url.startsWith("blob:")) {
                blobDownload(ctx, url, mimetype);
            } else if (url.startsWith("data:")) {
                dataDownload(ctx, url, mimetype);
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                httpDownload(ctx, url, userAgent, contentDisposition, mimetype, contentLength);
            } else {
                // file:// / content:// etc. — not supported by design
                ProfileManager.dispatchLog("[downloads] unsupported scheme: " + url);
            }
        } catch (Exception e) {
            ProfileManager.dispatchLog("[downloads] dispatch failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ registry ops

    public static String listJson() {
        synchronized (LOCK) {
            JSONArray arr = new JSONArray();
            List<Entry> all = new ArrayList<>(reg.values());
            all.sort((a, b) -> Long.compare(b.ts, a.ts));
            for (Entry e : all) {
                JSONObject j = e.toJson();
                j.remove("path"); // private on-device path stays off the wire
                arr.put(j);
            }
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("profile", profileName);
                o.put("downloads", arr);
            } catch (Exception ignored) {
            }
            return o.toString();
        }
    }

    public static Entry find(String id) {
        synchronized (LOCK) {
            return reg.get(id);
        }
    }

    public static String delete(String id) {
        synchronized (LOCK) {
            Entry e = reg.remove(id);
            if (e == null) return "{\"ok\":false,\"error\":\"download not found: " + id + "\"}";
            try {
                new File(e.path).delete();
            } catch (Exception ignored) {
            }
            saveMeta();
            return "{\"ok\":true,\"deleted\":\"" + id + "\"}";
        }
    }

    /** Cross-process lookup by id — scans every slot's meta.json (provider use). */
    public static Entry findByIdFromDisk(Context ctx, String id) {
        if (id == null || id.isEmpty()) return null;
        File root = rootDir(ctx);
        File[] slots = root.listFiles();
        if (slots != null) {
            for (File s : slots) {
                File meta = new File(s, "meta.json");
                if (!meta.isFile()) continue;
                try {
                    JSONArray arr = new JSONArray(readAll(meta));
                    for (int i = 0; i < arr.length(); i++) {
                        Entry e = Entry.fromJson(arr.getJSONObject(i));
                        if (id.equals(e.id)) {
                            File f = new File(e.path);
                            return f.isFile() ? e : null;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** Disk-based listing for ONE slot — used by the main-process API to
     *  aggregate downloads of profiles that are not currently running
     *  (their runner process is gone; meta.json is the source of truth). */
    public static String diskListJson(Context ctx, int slotNum) {
        JSONArray arr = new JSONArray();
        File meta = new File(rootDir(ctx), "slot" + slotNum + "/meta.json");
        if (meta.isFile()) {
            try {
                JSONArray raw = new JSONArray(readAll(meta));
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject e = raw.getJSONObject(i);
                    // drop the private path field from public listings
                    e.remove("path");
                    arr.put(e);
                }
            } catch (Exception ignored) {
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("downloads", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** content:// URI for a file (open in other apps, WebView upload callbacks). */
    public static Uri contentUri(Entry e) {
        return Uri.parse("content://" + AUTHORITY + "/" + e.id + "/"
                + Uri.encode(e.name == null ? "file.bin" : e.name));
    }

    // ------------------------------------------------------------ add / persist

    /**
     * v1.4.4: store an externally-uploaded file (POST /downloads/upload, raw body)
     * into THIS process's downloads registry. Runs in the profile's runner process
     * when the profile is running (main API proxies the bytes here). Raw request
     * body = file bytes; ?name= is the file name (defaults to upload.bin).
     */
    public static String saveUploaded(byte[] data, String name) {
        try {
            if (data == null || data.length == 0)
                return "{\"ok\":false,\"error\":\"empty body - send the raw file bytes as the request body\"}";
            if (dir == null)
                return "{\"ok\":false,\"error\":\"downloads not initialized (profile not running?)\"}";
            Entry e = newEntry(name, "", "external-upload");
            e.state = "done";
            e.size = data.length;
            e.got = data.length;
            e.path = fileFor(e).getAbsolutePath(); // v1.4.4 fix: attach/delete resolve through path
            try (OutputStream os = new FileOutputStream(fileFor(e))) {
                os.write(data);
            }
            put(e);
            ProfileManager.dispatchLog("[downloads] uploaded from outside: " + e.name
                    + " (" + data.length + "B)");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("download", e.toJson());
            return o.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"upload save failed: " + ex.getMessage() + "\"}";
        }
    }

    /**
     * v1.4.4: disk-direct variant for STOPPED profiles — the main process writes
     * the file and appends the entry to meta.json itself. Safe: the runner process
     * is gone, so there is no other meta.json writer to race with. Same layout the
     * runner's Downloads instance produces, so list/pull/attach/provider all work
     * unchanged after the profile starts again (loadMeta picks the entry up).
     */
    public static String saveUploadedToDisk(Context ctx, int slotNum, String profile,
                                            byte[] data, String name) {
        try {
            if (data == null || data.length == 0)
                return "{\"ok\":false,\"error\":\"empty body - send the raw file bytes as the request body\"}";
            File sdir = new File(rootDir(ctx), "slot" + slotNum);
            if (!sdir.exists()) sdir.mkdirs();
            List<Entry> entries = new ArrayList<>();
            File meta = new File(sdir, "meta.json");
            if (meta.isFile()) {
                try {
                    JSONArray raw = new JSONArray(readAll(meta));
                    for (int i = 0; i < raw.length(); i++)
                        entries.add(Entry.fromJson(raw.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
            Entry e = new Entry();
            e.id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            e.name = sanitize(name);
            e.mime = mimeForName(e.name);
            e.url = "external-upload";
            e.state = "done";
            e.ts = System.currentTimeMillis();
            e.profile = profile;
            e.size = data.length;
            e.got = data.length;
            e.path = new File(sdir, e.id + "-" + e.name).getAbsolutePath();
            try (OutputStream os = new FileOutputStream(e.path)) {
                os.write(data);
            }
            entries.add(e);
            JSONArray arr = new JSONArray();
            for (Entry x : entries) arr.put(x.toJson());
            File tmp = new File(sdir, "meta.json.tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (meta.exists()) meta.delete();
            tmp.renameTo(meta);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            JSONObject d = e.toJson();
            d.remove("path");
            o.put("download", d);
            return o.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"upload save failed: " + ex.getMessage() + "\"}";
        }
    }

    private static Entry newEntry(String name, String mime, String url) {
        Entry e = new Entry();
        e.id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        e.name = sanitize(name);
        e.mime = (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime))
                ? mimeForName(e.name) : mime;
        e.url = url == null ? "" : url;
        e.state = "running";
        e.ts = System.currentTimeMillis();
        e.profile = profileName;
        return e;
    }

    private static File fileFor(Entry e) {
        return new File(dir, e.id + "-" + e.name);
    }

    private static void put(Entry e) {
        synchronized (LOCK) {
            reg.put(e.id, e);
            saveMeta();
        }
    }

    private static void update(Entry e) {
        synchronized (LOCK) {
            reg.put(e.id, e);
            saveMeta();
        }
    }

    private static void saveMeta() {
        if (dir == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : reg.values()) arr.put(e.toJson());
            File tmp = new File(dir, "meta.json.tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            File meta = new File(dir, "meta.json");
            if (meta.exists()) meta.delete();
            tmp.renameTo(meta);
        } catch (Exception e) {
            ProfileManager.dispatchLog("[downloads] meta save failed: " + e.getMessage());
        }
    }

    private static void loadMeta(Context ctx) {
        reg.clear();
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) return;
        try {
            JSONArray arr = new JSONArray(readAll(meta));
            for (int i = 0; i < arr.length(); i++) {
                Entry e = Entry.fromJson(arr.getJSONObject(i));
                File f = new File(e.path);
                // file vanished (user cleared storage etc.) → drop the entry
                if (f.isFile()) {
                    // a previous session died mid-download → mark stale runs as errors
                    if ("running".equals(e.state)) {
                        e.state = "error";
                        e.error = "interrupted (app restarted)";
                    }
                    reg.put(e.id, e);
                }
            }
            saveMeta();
        } catch (Exception e) {
            ProfileManager.dispatchLog("[downloads] meta load failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ http(s) downloads

    private static void httpDownload(Context ctx, String url, String userAgent,
                                     String contentDisposition, String mimetype, long len) {
        String name = fileNameFrom(url, contentDisposition, mimetype);
        Entry e = newEntry(name, mimetype, url);
        e.size = Math.max(0, len);
        put(e);
        ProfileManager.dispatchLog("[downloads] start http: " + e.name + " ← " + url);
        Thread t = new Thread(() -> {
            File dst = fileFor(e);
            try {
                String current = url;
                for (int hop = 0; hop < 5; hop++) {
                    HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(false);
                    if (userAgent != null && !userAgent.isEmpty()) {
                        c.setRequestProperty("User-Agent", userAgent);
                    }
                    try {
                        String cookie = CookieManager.getInstance().getCookie(current);
                        if (cookie != null && !cookie.isEmpty()) {
                            c.setRequestProperty("Cookie", cookie);
                        }
                    } catch (Exception ignored) {
                    }
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null || loc.isEmpty()) throw new Exception("redirect without Location");
                        current = new URL(new URL(current), loc).toString();
                        continue;
                    }
                    if (code < 200 || code >= 300) {
                        c.disconnect();
                        throw new Exception("HTTP " + code);
                    }
                    // prefer response headers for name/mime/size
                    String cd = c.getHeaderField("Content-Disposition");
                    if (cd != null) {
                        String n2 = fileNameFrom(null, cd, null);
                        if (n2 != null && !n2.isEmpty()) {
                            e.name = sanitize(n2);
                            e.mime = mimeForName(e.name);
                        }
                    }
                    String cm = c.getContentType();
                    if (cm != null && !cm.isEmpty()) {
                        int sc = cm.indexOf(';');
                        e.mime = (sc > 0 ? cm.substring(0, sc) : cm).trim();
                    }
                    long cl = c.getContentLengthLong();
                    if (cl > 0) e.size = cl;
                    dst = fileFor(e);
                    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(dst)) {
                        byte[] buf = new byte[65536];
                        long total = 0;
                        long lastMark = 0;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            os.write(buf, 0, n);
                            total += n;
                            if (total - lastMark > 262144) { // progress every 256KB
                                e.got = total;
                                lastMark = total;
                            }
                        }
                        e.got = total;
                    }
                    c.disconnect();
                    break;
                }
                e.state = "done";
                e.size = dst.length();
                e.path = dst.getAbsolutePath();
                e.ts = System.currentTimeMillis();
                update(e);
                ProfileManager.dispatchLog("[downloads] done: " + e.name + " (" + e.size + "B)");
            } catch (Exception ex) {
                e.state = "error";
                e.error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                e.ts = System.currentTimeMillis();
                try {
                    dst.delete();
                } catch (Exception ignored) {
                }
                update(e);
                ProfileManager.dispatchLog("[downloads] failed: " + e.name + " — " + e.error);
            }
        }, "samcdp-dl");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ data: downloads

    private static void dataDownload(Context ctx, String url, String mimetype) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) throw new Exception("data URL without payload");
            String head = url.substring(0, comma);
            String payload = url.substring(comma + 1);
            byte[] bytes;
            if (head.contains(";base64")) {
                bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            } else {
                bytes = java.net.URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            }
            String mime = head.startsWith("data:") && head.length() > 5
                    ? head.substring(5, head.indexOf(';') > 0 ? head.indexOf(';') : head.length())
                    : (mimetype == null ? "" : mimetype);
            Entry e = newEntry(fileNameFrom(null, null, mime.isEmpty() ? mimetype : mime), mime, "data:");
            e.state = "done";
            e.size = bytes.length;
            File dst = fileFor(e);
            try (OutputStream os = new FileOutputStream(dst)) {
                os.write(bytes);
            }
            e.path = dst.getAbsolutePath();
            update(e);
            ProfileManager.dispatchLog("[downloads] done (data:): " + e.name + " (" + e.size + "B)");
        } catch (Exception ex) {
            ProfileManager.dispatchLog("[downloads] data: decode failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ blob: downloads

    /** JS-side receiver: base64 chunks streamed out of the page's blob. */
    private static class BlobSink {
        final Entry e;
        final File dst;
        OutputStream os;
        volatile boolean failed;

        BlobSink(Entry e, File dst) {
            this.e = e;
            this.dst = dst;
        }

        @android.webkit.JavascriptInterface
        public void onSize(long total) {
            e.size = total;
        }

        @android.webkit.JavascriptInterface
        public void onChunk(String b64) {
            try {
                if (os == null) os = new FileOutputStream(dst);
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                os.write(bytes);
                synchronized (this) {
                    e.got += bytes.length;
                    e.ts = System.currentTimeMillis(); // liveness marker
                }
            } catch (Exception ex) {
                failed = true;
            }
        }

        @android.webkit.JavascriptInterface
        public void onDone() {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
            if (failed) {
                e.state = "error";
                e.error = "blob write failed";
                try {
                    dst.delete();
                } catch (Exception ignored) {
                }
            } else {
                e.state = "done";
                e.size = dst.length();
                e.path = dst.getAbsolutePath();
            }
            e.ts = System.currentTimeMillis();
            update(e);
            ProfileManager.dispatchLog("[downloads] done (blob): " + e.name + " (" + e.size + "B)");
        }

        @android.webkit.JavascriptInterface
        public void onError(String msg) {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
            e.state = "error";
            e.error = msg == null ? "blob fetch failed" : msg;
            e.ts = System.currentTimeMillis();
            try {
                dst.delete();
            } catch (Exception ignored) {
            }
            update(e);
            ProfileManager.dispatchLog("[downloads] failed (blob): " + e.name + " — " + e.error);
        }
    }

    private static void blobDownload(Context ctx, String blobUrl, String mimetype) {
        final WebViewHandle wh = TabEngine.webViewForBlob(ctx, blobUrl);
        if (wh == null || wh.webView == null) {
            ProfileManager.dispatchLog("[downloads] blob: no live WebView — dropped");
            return;
        }
        String ext = extForMime(mimetype);
        Entry e = newEntry("blob-" + System.currentTimeMillis() + ext, mimetype, blobUrl);
        File dst = fileFor(e);
        put(e);
        ProfileManager.dispatchLog("[downloads] start blob: " + e.name + " (" + mimetype + ")");
        BlobSink sink = new BlobSink(e, dst);
        wh.webView.addJavascriptInterface(sink, "samcdpDL");
        String js = "(async function(){try{var r=await fetch(" + JSONObject.quote(blobUrl) + ");"
                + "if(!r.ok){window.samcdpDL.onError('HTTP '+r.status);return;}"
                + "var b=await r.blob();window.samcdpDL.onSize(b.size);"
                + "var CH=524288;"
                + "for(var off=0;off<b.size;off+=CH){"
                + "  var buf=await b.slice(off,off+CH).arrayBuffer();"
                + "  var u8=new Uint8Array(buf);var bin='';"
                + "  for(var i=0;i<u8.length;i+=32768){"
                + "    bin+=String.fromCharCode.apply(null,u8.subarray(i,i+32768));"
                + "  }"
                + "  window.samcdpDL.onChunk(btoa(bin));"
                + "}"
                + "window.samcdpDL.onDone();"
                + "}catch(e){window.samcdpDL.onError(String(e));}})()";
        Runnable eval = () -> {
            try {
                wh.webView.evaluateJavascript(js, null);
            } catch (Exception ex) {
                sink.onError("eval failed: " + ex.getMessage());
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) eval.run();
        else TabEngine.postToMain(eval);
        // cleanup: drop the bridge + stall watchdog
        Runnable cleanup = () -> {
            try {
                wh.webView.removeJavascriptInterface("samcdpDL");
            } catch (Exception ignored) {
            }
        };
        TabEngine.postToMainDelayed(cleanup, 1000);
        Thread watchdog = new Thread(() -> {
            long last = e.ts;
            while (true) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ignored) {
                    return;
                }
                synchronized (Downloads.LOCK) {
                    Entry cur = reg.get(e.id);
                    if (cur == null || !"running".equals(cur.state)) return;
                    last = cur.ts;
                }
                if (System.currentTimeMillis() - last > 120000) {
                    sink.onError("stalled >120s");
                    TabEngine.postToMain(cleanup);
                    return;
                }
            }
        }, "samcdp-dl-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Minimal handle so blobDownload can reach the right WebView (see TabEngine). */
    public static class WebViewHandle {
        public final android.webkit.WebView webView;

        public WebViewHandle(android.webkit.WebView wv) {
            webView = wv;
        }
    }

    // ------------------------------------------------------------ helpers

    private static String sanitize(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (n.isEmpty()) n = "download-" + System.currentTimeMillis() + ".bin";
        return n;
    }

    /** Filename from Content-Disposition → URL path → mime-based default. */
    private static String fileNameFrom(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*?=(?:UTF-8''|\")?([^\";]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(contentDisposition);
            if (m.find()) {
                try {
                    return java.net.URLDecoder.decode(m.group(1).trim(), "UTF-8");
                } catch (Exception e) {
                    return m.group(1).trim();
                }
            }
        }
        if (url != null) {
            String clean = url.split("[?#]")[0];
            int slash = clean.lastIndexOf('/');
            String last = slash >= 0 ? clean.substring(slash + 1) : clean;
            if (!last.isEmpty() && last.contains(".") && !last.endsWith(".")) {
                try {
                    return java.net.URLDecoder.decode(last, "UTF-8");
                } catch (Exception e) {
                    return last;
                }
            }
        }
        String ext = extForMime(mimetype);
        return "download-" + System.currentTimeMillis() + ext;
    }

    private static final Map<String, String> MIME_EXT = new HashMap<>();

    static {
        MIME_EXT.put("application/pdf", ".pdf");
        MIME_EXT.put("application/zip", ".zip");
        MIME_EXT.put("application/x-zip-compressed", ".zip");
        MIME_EXT.put("application/gzip", ".gz");
        MIME_EXT.put("application/x-tar", ".tar");
        MIME_EXT.put("application/csv", ".csv");
        MIME_EXT.put("text/csv", ".csv");
        MIME_EXT.put("application/msword", ".doc");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx");
        MIME_EXT.put("application/vnd.ms-excel", ".xls");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
        MIME_EXT.put("application/vnd.ms-powerpoint", ".ppt");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
        MIME_EXT.put("application/json", ".json");
        MIME_EXT.put("application/octet-stream", ".bin");
        MIME_EXT.put("text/plain", ".txt");
        MIME_EXT.put("text/html", ".html");
        MIME_EXT.put("image/png", ".png");
        MIME_EXT.put("image/jpeg", ".jpg");
        MIME_EXT.put("image/gif", ".gif");
        MIME_EXT.put("image/webp", ".webp");
        MIME_EXT.put("audio/mpeg", ".mp3");
        MIME_EXT.put("video/mp4", ".mp4");
    }

    private static String extForMime(String mime) {
        if (mime == null) return ".bin";
        String m = mime.split(";")[0].trim().toLowerCase();
        String ext = MIME_EXT.get(m);
        if (ext != null) return ext;
        String guessed = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(m);
        return guessed == null || guessed.isEmpty() ? ".bin" : "." + guessed;
    }

    private static String mimeForName(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
