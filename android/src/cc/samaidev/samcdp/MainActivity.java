package cc.samaidev.samcdp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements ProfileManager.LogSink {

    private View dot;
    private TextView status, url, token, logView;
    private Button btnToggle;
    private LinearLayout profilesBox;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };
    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dot = findViewById(R.id.dot);
        status = findViewById(R.id.status);
        url = findViewById(R.id.url);
        token = findViewById(R.id.token);
        btnToggle = findViewById(R.id.btn_toggle);
        logView = findViewById(R.id.log);
        profilesBox = findViewById(R.id.profiles);
        try {
            ((TextView) findViewById(R.id.version)).setText(
                    "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            ((TextView) findViewById(R.id.version)).setText("v1.0.2");
        }

        ProfileManager.setLogSink(this);

        btnToggle.setOnClickListener(v -> toggleService());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Auto-run service on launch (desktop parity: SamCDP auto-starts on launch)
        if (!CdpService.isRunning()) toggleService();
    }

    private void toggleService() {
        if (CdpService.isRunning()) {
            Intent i = new Intent(this, CdpService.class).setAction(CdpService.ACTION_STOP);
            startService(i);
            Toast.makeText(this, "SamCDP stopped", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(this, CdpService.class).setAction(CdpService.ACTION_RUN);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "SamCDP running", Toast.LENGTH_SHORT).show();
        }
        ui.postDelayed(this::render, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        ui.postDelayed(refresher, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    @Override
    public void onLog(String line) {
        synchronized (logBuf) {
            logBuf.append(line).append('\n');
            if (logBuf.length() > 20000) logBuf.delete(0, logBuf.length() - 10000);
        }
        ui.post(() -> {
            synchronized (logBuf) {
                logView.setText(logBuf.toString());
            }
        });
    }

    private void render() {
        boolean running = CdpService.isRunning();
        dot.setBackgroundResource(running ? R.drawable.dot_green : R.drawable.dot_red);
        status.setText(running ? "Running" : "Stopped");
        status.setTextColor(getColor(running ? R.color.accent : R.color.text_dim));
        Prefs p = new Prefs(this);
        url.setText(running ? "http://" + CdpService.localIp() + ":" + CdpService.api().port()
                : "http://—:" + p.port());
        token.setText("token: " + p.token() + "   (tap text to copy)");
        btnToggle.setText(running ? "Stop" : "Run");
        btnToggle.setBackgroundTintList(getColorStateList(running ? R.color.danger : R.color.accent));
        renderProfiles(running);
    }

    private void renderProfiles(boolean running) {
        List<Profile> list = ProfileManager.profiles();
        if (list.isEmpty()) return;
        if (profilesBox.getChildCount() == list.size()) {
            for (int i = 0; i < list.size(); i++) {
                Profile p = list.get(i);
                LinearLayout row = (LinearLayout) profilesBox.getChildAt(i);
                TextView info = (TextView) row.getChildAt(0);
                Button btn = (Button) row.getChildAt(1);
                String state = p.running
                        ? "running · " + ProfileManager.tabs(p).size() + " tabs"
                        + (p.autoStopAtMs > 0 ? " · " + Math.max(0, (p.autoStopAtMs - System.currentTimeMillis()) / 60000) + "m left" : "")
                        : (p.enabled ? "idle" : "disabled");
                info.setText(p.name + "\n" + state);
                btn.setText(p.running ? "Stop" : "Start");
                btn.setBackgroundTintList(getColorStateList(p.running ? R.color.danger : R.color.accent));
            }
            return;
        }
        profilesBox.removeAllViews();
        Prefs prefs = new Prefs(this);
        for (Profile p : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.card_bg);
            row.setPadding(36, 28, 36, 28);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 16;
            row.setLayoutParams(lp);

            TextView info = new TextView(this);
            info.setTextColor(getColor(R.color.text));
            info.setTextSize(13);
            info.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            info.setLayoutParams(ilp);
            row.addView(info);

            Button btn = new Button(this);
            btn.setTextSize(12);
            btn.setTypeface(android.graphics.Typeface.MONOSPACE);
            btn.setTextColor(getColor(R.color.bg));
            btn.setBackgroundTintList(getColorStateList(R.color.accent));
            btn.setPadding(30, 12, 30, 12);
            btn.setOnClickListener(v -> {
                try {
                    if (p.running) {
                        ProfileManager.stop(p);
                    } else {
                        ProfileManager.start(getApplicationContext(), prefs, p, 30);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
                render();
            });
            row.addView(btn);
            profilesBox.addView(row);
        }
    }
}
