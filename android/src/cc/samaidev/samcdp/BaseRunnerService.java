package cc.samaidev.samcdp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service hosting ONE profile inside a DEDICATED process
 * (android:process=":cdpN" in the manifest). The process data directory
 * suffix set in onCreate gives this profile its own cookie jar / storage —
 * true multi-cookie-pool isolation.
 */
public abstract class BaseRunnerService extends Service {

    public static final String ACTION_START = "cc.samaidev.samcdp.runner.START";
    public static final String ACTION_STOP = "cc.samaidev.samcdp.runner.STOP";
    private static final String CHANNEL_ID = "samcdp-runner";

    /** 1-based slot number, provided by the subclass. */
    protected abstract int slot();

    private static volatile ServerSocket tabServer;
    private static volatile ExecutorService pool;
    private static volatile String currentProfile;
    private static volatile boolean engineUp;
    private static volatile Class<?> activeClass;

    static void stopFromInside(Context ctx) {
        Class<?> cls = activeClass;
        if (cls == null) cls = RunnerService1.class;
        Intent i = new Intent(ctx, cls);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception e) {
            android.util.Log.w("samcdp", "stopFromInside failed: " + e.getMessage());
        }
    }

    /** Class of the runner service currently alive in THIS process (for TabActivity lookup). */
    static Class<?> activeRunnerClass() {
        return activeClass;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // MUST run before any WebView is created in this process.
        // Each runner process gets its own data dir -> own CookieManager -> own cookie pool.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("cdp" + slot());
            } catch (Exception e) {
                android.util.Log.w("samcdp", "suffix failed: " + e.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        activeClass = this.getClass();
        runForeground(intent);
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        String name = intent != null ? intent.getStringExtra("name") : null;
        int duration = intent != null ? intent.getIntExtra("duration_min", 0) : 0;
        if (name != null) {
            currentProfile = name;
            if (!engineUp) {
                startTabServer();
                engineUp = true;
            }
            TabEngine.start(this, name, duration);
        }
        return START_STICKY;
    }

    private void runForeground(Intent intent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID + slot(),
                    "CDP Profile runner " + slot(), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        String name = intent != null ? intent.getStringExtra("name") : "profile";
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, slot(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID + slot())
                : new Notification.Builder(this);
        b.setContentTitle("samcdp · " + name)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentText("Isolated profile runner #" + slot() + " (own cookie jar)")
                .setContentIntent(pOpen)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(0x5A40 + slot(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(0x5A40 + slot(), n);
        }
    }

    private void startTabServer() {
        try {
            int port = 9400 + slot();
            tabServer = new ServerSocket(port, 32, java.net.InetAddress.getByName("127.0.0.1"));
            tabServer.setReuseAddress(true);
            pool = Executors.newCachedThreadPool();
            Thread t = new Thread(() -> {
                while (tabServer != null && !tabServer.isClosed()) {
                    try {
                        Socket s = tabServer.accept();
                        pool.execute(() -> serve(s));
                    } catch (Exception e) {
                        sleep(50);
                    }
                }
            }, "tabserver-" + slot());
            t.setDaemon(true);
            t.start();
            ProfileManager.dispatchLog("[runner" + slot() + "] tab api on 127.0.0.1:" + port);
        } catch (Exception e) {
            ProfileManager.dispatchLog("[runner" + slot() + "] tab server FAILED: " + e.getMessage());
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            int contentLength = 0;
            String line;
            StringBuilder reqData = new StringBuilder();
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                reqData.append(line).append('\n');
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0 && contentLength < 4 * 1024 * 1024) {
                body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int r = in.read(body, off, contentLength - off);
                    if (r < 0) break;
                    off += r;
                }
            }

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            String rawPath = target, rawQuery = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                rawPath = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            String path = URLDecoder.decode(rawPath, "UTF-8");
            java.util.Map<String, String> query = new java.util.LinkedHashMap<>();
            for (String kv : rawQuery.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                query.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(eq < 0 ? "" : kv.substring(eq + 1), "UTF-8"));
            }

            String ct = "application/json; charset=utf-8";
            byte[] payload;
            String[] seg = path.split("/");

            if (path.equals("/info") && method.equals("GET")) {
                payload = TabEngine.infoJson().getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/tabs") && method.equals("GET")) {
                payload = TabEngine.listTabs().getBytes(StandardCharsets.UTF_8);
            } else if (seg.length == 2 && seg[1].equals("open") && method.equals("POST")) {
                payload = TabEngine.openTab(getApplicationContext(), query.get("url"), query.get("ua"))
                        .getBytes(StandardCharsets.UTF_8);
            } else if (seg.length == 4 && seg[1].equals("tabs") && method.equals("GET")
                    && seg[3].equals("screenshot")) {
                byte[] png = TabEngine.screenshot(seg[2]);
                if (png == null) {
                    ct = "application/json; charset=utf-8";
                    payload = "{\"ok\":false,\"error\":\"tab not found\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    ct = "image/png";
                    payload = png;
                }
            } else if (seg.length == 4 && seg[1].equals("tabs") && method.equals("POST")
                    && seg[3].equals("eval")) {
                String js = query.getOrDefault("js", "");
                if (js.isEmpty() && body.length > 0) {
                    try {
                        js = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("js", "");
                    } catch (Exception ignored) {
                    }
                }
                payload = TabEngine.eval(seg[2], js).getBytes(StandardCharsets.UTF_8);
            } else if (seg.length == 3 && seg[1].equals("tabs") && method.equals("DELETE")) {
                payload = TabEngine.closeTab(seg[2]).getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/show") && method.equals("POST")) {
                payload = TabEngine.showTab(getApplicationContext(), query.get("tab"))
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/hide") && method.equals("POST")) {
                payload = TabEngine.hide().getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/visible") && method.equals("GET")) {
                payload = TabEngine.visibleJson().getBytes(StandardCharsets.UTF_8);
            } else {
                payload = "{\"ok\":false,\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            }

            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + ct
                    + "\r\nContent-Length: " + payload.length
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            socket.close();
        } catch (Exception ignored) {
            try {
                socket.close();
            } catch (Exception ignored2) {
            }
        }
    }

    private void shutdown() {
        try {
            TabEngine.stop(this);
        } catch (Exception ignored) {
        }
        try {
            if (tabServer != null) tabServer.close();
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdownNow();
        tabServer = null;
        engineUp = false;
        currentProfile = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (currentProfile != null) {
            try {
                TabEngine.stop(this);
            } catch (Exception ignored) {
            }
        }
        try {
            if (tabServer != null) tabServer.close();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String readLine(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') break;
            if (prev != -1) buf.write(prev);
            prev = c;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
