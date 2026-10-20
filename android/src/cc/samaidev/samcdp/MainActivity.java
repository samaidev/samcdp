package cc.samaidev.samcdp;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements ProfileManager.LogSink {

    private TextView remoteUrl, tunnelStatus, statusText, logView;
    private View dot;
    private EditText aitunKeyInput, tokenInput;
    private LinearLayout profilesBox;
    private Button btnTunnel;
    private TunnelManager.Listener tunnelListener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };
    private int renderedProfileCount = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        remoteUrl = findViewById(R.id.remote_url);
        tunnelStatus = findViewById(R.id.tunnel_status);
        statusText = findViewById(R.id.status);
        dot = findViewById(R.id.dot);
        aitunKeyInput = findViewById(R.id.aitun_key);
        tokenInput = findViewById(R.id.token_input);
        profilesBox = findViewById(R.id.profiles);
        logView = findViewById(R.id.log);
        btnTunnel = findViewById(R.id.btn_tunnel);

        ProfileManager.setLogSink(this);
        // addListener + onDestroy removeListener：不影响 CdpService 的通知刷新监听，也不随 Activity 重建而泄漏
        tunnelListener = this::uiPostRender;
        TunnelManager.addListener(tunnelListener);

        Prefs p = new Prefs(this);
        aitunKeyInput.setText(p.aitunKey());
        tokenInput.setText(p.token());
        if (p.tunnelMode().equals("subdomain")) {
            ((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).setChecked(true);
        } else {
            ((android.widget.RadioButton) findViewById(R.id.mode_temp)).setChecked(true);
        }

        findViewById(R.id.btn_copy).setOnClickListener(v -> {
            // 隧道连接中还没有可用远程地址，复制局域网地址会造成误导
            if (TunnelManager.state() == TunnelManager.State.CONNECTING) {
                Toast.makeText(this, "隧道连接中，上线后即可复制远程地址", Toast.LENGTH_SHORT).show();
                return;
            }
            String s = remoteUrl.getText().toString();
            // 复制即带 token，拿去就能直接调 API（与桌面设置页 copyRemoteUrl 行为一致）
            String token = tokenInput.getText().toString().trim();
            String full = token.isEmpty() ? s : s + "?token=" + token;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", full));
            Toast.makeText(this, "已复制 " + full, Toast.LENGTH_SHORT).show();
        });

        btnTunnel.setOnClickListener(v -> toggleTunnel());

        findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryOptimizationExemption());

        findViewById(R.id.btn_add_profile).setOnClickListener(v -> {
            List<Profile> list = ProfileManager.profiles();
            if (list.size() >= ProfileManager.MAX_PROFILES) {
                Toast.makeText(this, "最多 " + ProfileManager.MAX_PROFILES + " 个 Profile（独立进程隔离）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            int n = list.size() + 1;
            String name = "agent-" + n;
            while (ProfileManager.find(name) != null) {
                n++;
                name = "agent-" + n;
            }
            Profile np = new Profile(name, 9334 + ProfileManager.profiles().size(), true);
            ProfileManager.profiles().add(np);
            ProfileManager.saveProfiles();
            ProfileManager.dispatchLog("[config] profile added: " + name + " (port " + np.port + ")");
            renderedProfileCount = -1;
            render();
        });

        findViewById(R.id.btn_regen).setOnClickListener(v -> {
            String t = Prefs.randomToken();
            tokenInput.setText(t);
            Toast.makeText(this, "已重新生成，保存设置后生效", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // desktop parity: auto-run on launch
        if (!CdpService.isRunning()) {
            toggleService();
        }
    }

    private void uiPostRender() {
        ui.post(this::render);
    }

    private void toggleService() {
        if (CdpService.isRunning()) {
            Intent i = new Intent(this, CdpService.class).setAction(CdpService.ACTION_STOP);
            startService(i);
        } else {
            Intent i = new Intent(this, CdpService.class).setAction(CdpService.ACTION_RUN);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
        ui.postDelayed(this::render, 400);
    }

    private void toggleTunnel() {
        Prefs p = new Prefs(this);
        // persist chosen key/mode first
        p.setAitunKey(aitunKeyInput.getText().toString().trim());
        p.setTunnelMode(((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).isChecked()
                ? "subdomain" : "temp");
        if (TunnelManager.state() == TunnelManager.State.ONLINE
                || TunnelManager.state() == TunnelManager.State.CONNECTING) {
            TunnelManager.stop();
            ProfileManager.dispatchLog("[aitun] stopped by user");
        } else {
            if (!CdpService.isRunning()) {
                toggleService();
            }
            boolean subdomain = p.tunnelMode().equals("subdomain");
            if (subdomain && p.aitunKey().isEmpty()) {
                Toast.makeText(this, "子域名模式需要填入 aitun Key", Toast.LENGTH_LONG).show();
                return;
            }
            TunnelManager.start(getApplicationContext(), p.tunnelMode(), p.aitunKey(), CdpService.api().port());
        }
        ui.postDelayed(this::render, 500);
    }

    /** samdocker-style battery optimization button. */
    private void requestBatteryOptimizationExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                Toast.makeText(this, "已忽略电池优化，可在系统设置中撤回", Toast.LENGTH_SHORT).show();
            } else {
                @SuppressWarnings("BatteryLife")
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg));
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开电池优化设置: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSettings() {
        Prefs p = new Prefs(this);
        String oldToken = p.token();
        String newToken = tokenInput.getText().toString().trim();
        boolean tunnelWasUp = TunnelManager.state() == TunnelManager.State.ONLINE;
        String tunnelMode = ((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).isChecked()
                ? "subdomain" : "temp";
        String key = aitunKeyInput.getText().toString().trim();

        p.setAitunKey(key);
        p.setTunnelMode(tunnelMode);
        if (!newToken.isEmpty() && !newToken.equals(oldToken)) {
            p.setToken(newToken);
        }
        ProfileManager.saveProfiles();
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();

        // restart server to apply token; restart tunnel if it was online
        boolean wasRunning = CdpService.isRunning();
        if (wasRunning) {
            TunnelManager.stop();
            Intent stop = new Intent(this, CdpService.class).setAction(CdpService.ACTION_STOP);
            startService(stop);
        }
        Intent run = new Intent(this, CdpService.class).setAction(CdpService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(run);
        else startService(run);
        if (tunnelWasUp) {
            if (tunnelMode.equals("subdomain") && key.isEmpty()) {
                // never silently restart as something else; say exactly what was skipped
                ProfileManager.dispatchLog("[aitun] auto-restart skipped: subdomain mode without key");
                Toast.makeText(this, "隧道未自动重启：子域名模式需要填入 aitun Key", Toast.LENGTH_LONG).show();
            } else {
                ui.postDelayed(() -> TunnelManager.start(getApplicationContext(), tunnelMode, key,
                        new Prefs(this).port()), 2500);
            }
        }
        ui.postDelayed(this::render, 600);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        ui.postDelayed(refresher, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tunnelListener != null) {
            TunnelManager.removeListener(tunnelListener);
            tunnelListener = null;
        }
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
            if (logBuf.length() > 16000) logBuf.delete(0, logBuf.length() - 8000);
        }
        ui.post(() -> {
            synchronized (logBuf) {
                logView.setText(logBuf.toString());
            }
        });
    }

    private void render() {
        Prefs p = new Prefs(this);
        boolean running = CdpService.isRunning();
        String lan = running ? "http://" + CdpService.localIp() + ":" + CdpService.api().port()
                : "http://" + CdpService.detectIp() + ":" + p.port();
        // 远程地址规则：隧道在线→显示临时代理/子域名地址；连接中→占位提示（绝不显示局域网地址）；
        // 隧道未启用→显示局域网地址（此时它是唯一可达入口）
        TunnelManager.State cur = TunnelManager.state();
        String remote;
        if (cur == TunnelManager.State.ONLINE) {
            remote = TunnelManager.url();
            remoteUrl.setTextColor(getColor(R.color.url_green));
        } else if (cur == TunnelManager.State.CONNECTING) {
            remote = "隧道连接中…（上线后显示临时代理/子域名地址）";
            remoteUrl.setTextColor(getColor(R.color.text_dim));
        } else {
            remote = lan;
            remoteUrl.setTextColor(getColor(R.color.url_green));
        }
        remoteUrl.setText(remote);

        // status card (desktop parity: service running = 运行中)
        List<Profile> list = ProfileManager.profiles();
        int runningCount = 0;
        for (Profile pr : list) if (pr.running) runningCount++;
        boolean up = running;
        dot.setBackgroundResource(up ? R.drawable.dot_green : R.drawable.dot_red);
        statusText.setText(up ? "运行中（" + runningCount + " profiles）" : "已停止");
        statusText.setTextColor(getColor(up ? R.color.status_green : R.color.text_dim));

        // tunnel line
        TunnelManager.State ts = TunnelManager.state();
        if (ts == TunnelManager.State.ONLINE) {
            String runMode = "subdomain".equals(TunnelManager.mode()) ? "子域名" : "临时代理";
            String txt = "● 隧道在线: " + TunnelManager.url() + "（" + runMode + "）";
            // unsaved-edit divergence warning: what the user sees in the form
            // must be visibly reconciled against what is actually running
            String uiMode = ((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).isChecked()
                    ? "subdomain" : "temp";
            String uiKey = aitunKeyInput.getText().toString().trim();
            boolean modeDiffers = !uiMode.equals(TunnelManager.mode());
            boolean keyDiffers = "subdomain".equals(TunnelManager.mode())
                    && !uiKey.equals(TunnelManager.keyUsed());
            if (modeDiffers || keyDiffers) {
                txt += "\n⚠ 界面选项与运行中隧道不一致（运行中：" + runMode
                        + "）。改动需停止隧道后重新启动才会生效";
                tunnelStatus.setTextColor(getColor(R.color.dot_orange));
            } else {
                tunnelStatus.setTextColor(getColor(R.color.status_green));
            }
            tunnelStatus.setText(txt);
            btnTunnel.setText("停止隧道");
        } else if (ts == TunnelManager.State.CONNECTING) {
            String connecting = "● 隧道连接中…";
            if (TunnelManager.lastError() != null) {
                connecting += "（上次错误: " + TunnelManager.lastError() + "）";
            }
            tunnelStatus.setText(connecting);
            tunnelStatus.setTextColor(getColor(R.color.dot_orange));
            btnTunnel.setText("停止隧道");
        } else if (ts == TunnelManager.State.ERROR) {
            tunnelStatus.setText("● 隧道错误: " + TunnelManager.lastError());
            tunnelStatus.setTextColor(getColor(R.color.danger));
            btnTunnel.setText("启动隧道");
        } else {
            tunnelStatus.setText("隧道未启动（局域网: " + lan + "）");
            tunnelStatus.setTextColor(getColor(R.color.text_dim));
            btnTunnel.setText("启动隧道");
        }

        renderProfiles(list);
    }

    private void renderProfiles(List<Profile> list) {
        if (renderedProfileCount != list.size()) {
            profilesBox.removeAllViews();
            for (int i = 0; i < list.size(); i++) profilesBox.addView(buildProfileRow(list, i));
            renderedProfileCount = list.size();
        }
        for (int i = 0; i < profilesBox.getChildCount() && i < list.size(); i++) {
            Profile pr = list.get(i);
            LinearLayout row = (LinearLayout) profilesBox.getChildAt(i);
            TextView info = (TextView) row.getChildAt(0);
            Button del = (Button) row.getChildAt(1);
            Switch sw = (Switch) row.getChildAt(2);
            String state;
            if (pr.running) {
                String extra = pr.autoStopAtMs > 0
                        ? " · " + Math.max(0, (pr.autoStopAtMs - System.currentTimeMillis()) / 60000) + "m"
                        : "";
                state = "● 运行中 · runner #" + pr.slot + extra;
            } else {
                state = pr.enabled ? "○ 空闲" : "○ 已禁用";
            }
            info.setText(pr.name + "\nPort: " + pr.port + "\n" + state);
            info.setTextColor(getColor(pr.running ? R.color.heading : R.color.text));
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(pr.running);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Prefs prefs = new Prefs(this);
                try {
                    if (isChecked) {
                        ProfileManager.start(getApplicationContext(), pr, 0);
                    } else {
                        ProfileManager.stop(getApplicationContext(), pr);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    ui.postDelayed(this::render, 300);
                }
                ui.postDelayed(this::render, 700);
            });
        }
    }

    private LinearLayout buildProfileRow(List<Profile> list, int index) {
        Profile pr = list.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(36, 30, 30, 30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 18;
        row.setLayoutParams(lp);

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(getColor(R.color.text));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(ilp);
        row.addView(info);

        Button show = new Button(this);
        show.setText("显示");
        show.setAllCaps(false);
        show.setTextSize(12);
        show.setTextColor(0xFFFFFFFF);
        show.setBackground(getDrawable(R.drawable.btn_orange));
        show.setPadding(26, 10, 26, 10);
        show.setOnClickListener(v -> {
            if (!pr.running) {
                Toast.makeText(this, "profile 未运行，先打开开关启动", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                // opens the profile's TabActivity full-screen (frontend / human handover)
                ProfileManager.show(pr, null);
            } catch (Exception e) {
                Toast.makeText(this, "显示失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(show);

        Button del = new Button(this);
        del.setText("删除");
        del.setAllCaps(false);
        del.setTextSize(12);
        del.setTextColor(getColor(R.color.danger));
        del.setBackground(getDrawable(R.drawable.btn_delete));
        del.setPadding(26, 10, 26, 10);
        del.setOnClickListener(v -> {
            ProfileManager.stop(getApplicationContext(), pr);
            ProfileManager.profiles().remove(pr);
            ProfileManager.saveProfiles();
            renderedProfileCount = -1;
            render();
        });
        row.addView(del);

        Switch sw = new Switch(this);
        sw.setPadding(20, 0, 0, 0);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean c) -> { });
        row.addView(sw);
        return row;
    }
}
