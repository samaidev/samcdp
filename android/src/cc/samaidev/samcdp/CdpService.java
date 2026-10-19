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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class CdpService extends Service {

    public static final String ACTION_RUN = "cc.samaidev.samcdp.RUN";
    public static final String ACTION_STOP = "cc.samaidev.samcdp.STOP";
    private static final String CHANNEL_ID = "samcdp";
    private static final int NOTIF_ID = 0x5A4D;

    private static volatile Context appCtx;
    private static volatile String localIp = "127.0.0.1";
    private static volatile Api api;

    public static Context appContext() {
        return appCtx;
    }

    public static String localIp() {
        return localIp;
    }

    public static boolean isRunning() {
        return api != null;
    }

    public static Api api() {
        return api;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_RUN;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        runForeground();
        startServer();
        return START_STICKY;
    }

    private void runForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        String token = new Prefs(this).token();
        int port = new Prefs(this).port();
        String url = "http://" + localIp() + ":" + port;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notif_title))
                .setSmallIcon(R.drawable.ic_stat)
                .setContentText(url + "  ·  token " + token)
                .setContentIntent(pOpen)
                .setOngoing(true);

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startServer() {
        if (api != null) return;
        Prefs prefs = new Prefs(this);
        localIp = detectIp();
        ProfileManager.loadProfiles(prefs);
        Api server = new Api(prefs.port(), prefs.token());
        try {
            server.start();
            api = server;
            ProfileManager.log("server listening on http://" + localIp + ":" + prefs.port()
                    + "  (token " + prefs.token() + ")");
        } catch (Exception e) {
            ProfileManager.log("server start FAILED: " + e.getMessage());
            stopSelf();
        }
    }

    private void shutdown() {
        if (api != null) {
            api.stop();
            api = null;
        }
        ProfileManager.stopAll();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        ProfileManager.log("service stopped");
    }

    @Override
    public void onDestroy() {
        if (api != null) {
            api.stop();
            api = null;
        }
        ProfileManager.stopAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static String detectIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
