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

    private static volatile Process process;
    private static volatile State state = State.STOPPED;
    private static volatile String url;
    private static volatile String lastError;
    private static volatile String mode = "temp";
    private static volatile String keyUsed = "";
    private static volatile Listener listener;
    private static volatile int serverPort = -1;
    private static Thread ioThread;

    public static void setListener(Listener l) {
        listener = l;
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

    private static void fire() {
        Listener l = listener;
        if (l != null) l.onTunnelChanged();
    }

    public static synchronized void start(Context ctx, String tunnelMode, String key, int port) {
        if (state == State.CONNECTING || state == State.ONLINE) stop();
        mode = "subdomain".equals(tunnelMode) ? "subdomain" : "temp";
        keyUsed = key == null ? "" : key.trim();
        serverPort = port;
        state = State.CONNECTING;
        lastError = null;
        url = null;
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

        String[] cmd = keyUsed.isEmpty() || !mode.equals("subdomain")
                ? new String[]{bin, "-p", String.valueOf(port), "-no-p2p"}
                : new String[]{bin, "-k", keyUsed, "-p", String.valueOf(port), "-no-p2p"};

        // Go static binaries can't use Android DNS (no /etc/resolv.conf → falls back to ::1:53).
        // Resolve the server host once via Android's resolver and pass the IP via -s.
        String[] cmdF = cmd;
        try {
            java.net.InetAddress ip = java.net.InetAddress.getByName("aitun.cc");
            String serverIp = ip.getHostAddress();
            String[] cmd2 = new String[cmd.length + 2];
            cmd2[0] = cmd[0];
            cmd2[1] = "-s";
            cmd2[2] = serverIp + ":6639";
            System.arraycopy(cmd, 1, cmd2, 3, cmd.length - 1);
            cmdF = cmd2;
            ProfileManager.dispatchLog("[aitun] server resolved to " + serverIp);
        } catch (Exception e) {
            ProfileManager.dispatchLog("[aitun] DNS resolve failed, using hostname: " + e.getMessage());
        }
        final String[] finalCmd = cmdF;

        Thread t = new Thread(() -> {
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
                            url = m.group(1);
                            state = State.ONLINE;
                            ProfileManager.dispatchLog("[aitun] ONLINE → " + url);
                            fire();
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

    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteR(k);
        f.delete();
    }
}
