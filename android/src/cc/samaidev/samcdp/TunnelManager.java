package cc.samaidev.samcdp;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the bundled aitun tunnel client (libaitunclient.so, from SamDocker),
 * executed from nativeLibraryDir with an isolated HOME.
 *
 * Modes (aitun client v4.x):
 *   临时代理  -p PORT -no-p2p                 → [OK] Proxy URL: https://aitun.cc/CODE (24h)
 *   子域名    -k ait_KEY -p PORT -no-p2p      → [OK] Subdomain mode: https://name.aitun.cc
 */
public class TunnelManager {

    public interface Listener {
        void onTunnelChanged();
    }

    public enum State { STOPPED, CONNECTING, ONLINE, ERROR }

    private static final Pattern P_URL = Pattern.compile("\\[OK\\]\\s+(?:Proxy URL|Subdomain mode):\\s+(\\S+)");
    private static final Pattern P_ERR = Pattern.compile("^\\s*Error:\\s+(.+)$");
    private static final Pattern P_ERR2 = Pattern.compile("\\[Error\\]\\s+(.+)$");

    // last-resort server IP if both system resolver and DoH fail (verified 2025-10-19)
    private static final String SERVER_HOST = "aitun.cc";
    private static final String SERVER_FALLBACK_IP = "43.160.208.156";
    private static final String DOH_ENDPOINT = "https://223.5.5.5/resolve?name=";

    private static volatile Process process;
    private static volatile State state = State.STOPPED;
    private static volatile String url;
    private static volatile String lastError;
    private static volatile String mode = "temp";
    private static volatile String keyUsed = "";
    // multiple surfaces listen: MainActivity (UI) + CdpService (notification refresh)
    private static final java.util.List<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile int serverPort = -1;
    private static Thread ioThread;

    public static void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void setListener(Listener l) {
        listeners.clear();
        addListener(l);
    }

    public static State state() {
        return state;
    }

    public static String url() {
        return url;
    }

    public static String lastError() {
        return lastError;
    }

    public static String mode() {
        return mode;
    }

    public static String keyUsed() {
        return keyUsed;
    }

    private static void fire() {
        for (Listener l : listeners) {
            try {
                l.onTunnelChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized void start(Context ctx, String tunnelMode, String key, int port) {
        if (state == State.CONNECTING || state == State.ONLINE) stop();
        mode = "subdomain".equals(tunnelMode) ? "subdomain" : "temp";
        keyUsed = key == null ? "" : key.trim();
        serverPort = port;
        url = null;
        lastError = null;
        // hard rule: subdomain without key is refused, never silently downgraded to temp
        if (mode.equals("subdomain") && keyUsed.isEmpty()) {
            state = State.ERROR;
            lastError = "子域名模式需要填入 aitun Key（留空请改选临时代理）";
            fire();
            ProfileManager.dispatchLog("[aitun] refused: subdomain mode without key");
            return;
        }
        state = State.CONNECTING;
        fire();

        String bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libaitunclient.so")
                .getAbsolutePath();
        File home = new File(ctx.getFilesDir(), "aitun-home");
        // fresh HOME per start so each temp tunnel gets a fresh identity
        try {
            deleteR(home);
            home.mkdirs();
        } catch (Exception ignored) {
        }

        String[] cmd = mode.equals("subdomain")
                ? new String[]{bin, "-k", keyUsed, "-p", String.valueOf(port), "-no-p2p"}
                : new String[]{bin, "-p", String.valueOf(port), "-no-p2p"};

        // Go static binaries can't use Android DNS (no /etc/resolv.conf → falls back to ::1:53).
        // Resolve the server host once via a 3-step chain and pass the IP via -s:
        //   1) system resolver  2) AliDNS DoH over IP (survives broken LAN DNS)
        //   3) hardcoded fallback IP (verified 2025-10-19)
        final String[] baseCmd = cmd;

        Thread t = new Thread(() -> {
            // DNS 解析 + 命令拼装必须在后台线程：InetAddress.getByName 在主线程
            // 会抛 NetworkOnMainThreadException（点击启动隧道即闪退的根因）
            final String[] finalCmd = buildServerCmd(baseCmd);
            int consecutiveErrors = 0;
            boolean everOnline = false;
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(finalCmd);
                    pb.environment().put("HOME", home.getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    process = p;
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        ProfileManager.dispatchLog("[aitun] " + line.trim());
                        Matcher m = P_URL.matcher(line);
                        if (m.find()) {
                            String got = m.group(1);
                            // sanity: returned URL style must match the requested mode,
                            // otherwise the UI would claim one mode while serving another
                            boolean looksSubdomain = got.matches("https://[A-Za-z0-9-]+\\.aitun\\.cc.*");
                            boolean looksTemp = got.matches("https://aitun\\.cc/.+");
                            boolean mismatch = (mode.equals("subdomain") && !looksSubdomain)
                                    || (mode.equals("temp") && !looksTemp);
                            if (mismatch) {
                                lastError = "服务器返回与所选模式不符（选了"
                                        + (mode.equals("subdomain") ? "子域名" : "临时代理")
                                        + "，实际: " + got + "），已停止";
                                state = State.ERROR;
                                url = got;
                                ProfileManager.dispatchLog("[aitun] mode mismatch: " + got);
                                fire();
                                Process pp = process;
                                if (pp != null) pp.destroy();
                                return;
                            }
                            url = got;
                            state = State.ONLINE;
                            everOnline = true;
                            consecutiveErrors = 0;
                            ProfileManager.dispatchLog("[aitun] ONLINE (" + mode + ") → " + url);
                            fire();
                        }
                        Matcher e2 = P_ERR2.matcher(line);
                        if (e2.find() && state != State.ONLINE) {
                            lastError = e2.group(1).trim();
                            consecutiveErrors++;
                            fire(); // stay CONNECTING but let the UI surface the last error
                            // never-online client retrying forever = eternal spinner; give up
                            if (!everOnline && consecutiveErrors >= 3) {
                                ProfileManager.dispatchLog("[aitun] giving up after "
                                        + consecutiveErrors + " consecutive errors");
                                state = State.ERROR;
                                fire();
                                Process pp = process;
                                if (pp != null) pp.destroy();
                                return;
                            }
                        }
                        Matcher e = P_ERR.matcher(line);
                        if (e.find()) {
                            lastError = e.group(1).trim();
                            if (lastError.contains("local service not available") && attempt < 4) {
                                ProfileManager.dispatchLog("[aitun] local service not ready, retrying ("
                                        + attempt + "/4)");
                                break; // retry outer loop
                            }
                            state = State.ERROR;
                            fire();
                        }
                    }
                    int code = p.waitFor();
                    process = null;
                    if (state == State.STOPPED) return;   // stopped by user
                    if (attempt < 4 && code != 0) {
                        Thread.sleep(2500);
                        continue;
                    }
                    if (state != State.ONLINE && state != State.ERROR) {
                        state = State.ERROR;
                        lastError = "client exited (" + code + ")";
                        fire();
                    }
                    return;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    state = State.ERROR;
                    fire();
                    return;
                }
            }
        }, "aitun-tunnel");
        ioThread = t;
        t.start();
    }

    /** Resolve aitun.cc once (system DNS → DoH → fallback IP) and append -s IP:6639.
     *  MUST run on a worker thread — blocks on DNS. */
    private static String[] buildServerCmd(String[] cmd) {
        String serverIp = null;
        try {
            serverIp = java.net.InetAddress.getByName(SERVER_HOST).getHostAddress();
            ProfileManager.dispatchLog("[aitun] server resolved via system DNS: " + serverIp);
        } catch (Exception e) {
            ProfileManager.dispatchLog("[aitun] system DNS failed: " + e.getMessage());
        }
        if (serverIp == null || !serverIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            serverIp = dohResolve(SERVER_HOST);
            if (serverIp != null)
                ProfileManager.dispatchLog("[aitun] server resolved via DoH(223.5.5.5): " + serverIp);
        }
        if (serverIp == null) {
            serverIp = SERVER_FALLBACK_IP;
            ProfileManager.dispatchLog("[aitun] DNS unavailable, using fallback IP: " + serverIp);
        }
        try {
            String[] cmd2 = new String[cmd.length + 2];
            cmd2[0] = cmd[0];
            cmd2[1] = "-s";
            cmd2[2] = serverIp + ":6639";
            System.arraycopy(cmd, 1, cmd2, 3, cmd.length - 1);
            return cmd2;
        } catch (Exception e) {
            ProfileManager.dispatchLog("[aitun] -s param failed, using hostname: " + e.getMessage());
            return cmd;
        }
    }

    public static synchronized void stop() {
        state = State.STOPPED;
        url = null;
        fire();
        Process p = process;
        if (p != null) {
            p.destroy(); // SIGTERM → graceful "Received signal, shutting down..."
            process = null;
        }
        if (ioThread != null) ioThread.interrupt();
    }

    public static String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("state", state.name());
            o.put("url", url == null ? JSONObject.NULL : url);
            o.put("mode", mode);
            o.put("error", lastError == null ? JSONObject.NULL : lastError);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** AliDNS DoH JSON API over bare IP — works even when the LAN DNS is broken. */
    private static String dohResolve(String host) {
        try {
            java.net.URL u = new java.net.URL(DOH_ENDPOINT + host + "&type=A");
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                org.json.JSONArray ans = o.optJSONArray("Answer");
                if (ans != null) {
                    for (int i = 0; i < ans.length(); i++) {
                        JSONObject a = ans.optJSONObject(i);
                        if (a != null && a.optInt("type") == 1) {
                            String ip = a.optString("data");
                            if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            ProfileManager.dispatchLog("[aitun] DoH failed: " + e.getMessage());
        }
        return null;
    }

    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteR(k);
        f.delete();
    }
}
