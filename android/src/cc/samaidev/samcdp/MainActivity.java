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
        L.load(this); // v1.4.0: language pref before any UI string is built
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

        applyStaticTexts(); // v1.4.0: XML labels come from the dictionary

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
                Toast.makeText(this, L.t("main.copyConnecting"), Toast.LENGTH_SHORT).show();
                return;
            }
            String s = remoteUrl.getText().toString();
            // 复制即带 token，拿去就能直接调 API（与桌面设置页 copyRemoteUrl 行为一致）
            String token = tokenInput.getText().toString().trim();
            String full = token.isEmpty() ? s : s + "?token=" + token;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", full));
            Toast.makeText(this, L.f("main.copied", full), Toast.LENGTH_SHORT).show();
        });

        btnTunnel.setOnClickListener(v -> toggleTunnel());

        findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryOptimizationExemption());

        // v1.4.0: language switch — persist then recreate so every static label re-renders
        android.widget.RadioButton langEn = findViewById(R.id.lang_en);
        android.widget.RadioButton langZh = findViewById(R.id.lang_zh);
        (L.zh() ? langZh : langEn).setChecked(true);
        android.widget.RadioGroup langGroup = findViewById(R.id.lang_group);
        langGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String want = checkedId == R.id.lang_zh ? "zh" : "en";
            if (!want.equals(L.lang())) {
                L.set(this, want);
                recreate();
            }
        });

        findViewById(R.id.btn_add_profile).setOnClickListener(v -> {
            List<Profile> list = ProfileManager.profiles();
            if (list.size() >= ProfileManager.MAX_PROFILES) {
                Toast.makeText(this, L.f("main.maxProfiles", ProfileManager.MAX_PROFILES),
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
            // 立即持久化 + 热应用到运行中的 API server，无需重启服务/隧道
            new Prefs(this).setToken(t);
            Api api = CdpService.api();
            if (api != null) api.setToken(t);
            ProfileManager.dispatchLog("[config] token regenerated"
                    + (api != null ? " and hot-applied" : " (saved; applies when server starts)"));
            Toast.makeText(this, L.t("main.regenDone"), Toast.LENGTH_SHORT).show();
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

    /** v1.4.0: override every Chinese default string baked into activity_main.xml. */
    private void applyStaticTexts() {
        setText(R.id.title, "main.title");
        setText(R.id.remote_label, "main.remoteAddr");
        setText(R.id.btn_copy, "main.copy");
        setText(R.id.api_hint, "main.apiHint");
        setText(R.id.tunnel_section, "main.aitunTunnel");
        setHint(R.id.aitun_key, "main.keyFieldHint");
        setText(R.id.key_hint2, "main.keyHint2");
        setText(R.id.mode_temp, "main.modeTempRadio");
        setText(R.id.mode_subdomain, "main.modeSubRadio");
        setText(R.id.btn_tunnel, "main.startTunnel");
        setText(R.id.btn_battery, "main.battery");
        setText(R.id.tunnel_status, "main.tunnelOffStatic");
        setText(R.id.status_section, "main.runStatus");
        setText(R.id.status, "main.stoppedStatic");
        setText(R.id.profiles_section, "main.profiles");
        setText(R.id.btn_add_profile, "main.addProfile");
        setText(R.id.token_hint, "main.tokenHint");
        setText(R.id.btn_regen, "main.regen");
        setText(R.id.btn_save, "main.saveSettings");
        setText(R.id.log_section, "main.logs");
        setText(R.id.lang_label, "main.langLabel");
        setText(R.id.lang_en, "main.langEn");
        setText(R.id.lang_zh, "main.langZh");
    }

    private void setText(int viewId, String key) {
        View v = findViewById(viewId);
        if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setText(L.t(key));
    }

    private void setHint(int viewId, String key) {
        View v = findViewById(viewId);
        if (v instanceof android.widget.EditText) ((android.widget.EditText) v).setHint(L.t(key));
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
                Toast.makeText(this, L.t("main.subdomainNeedKey"), Toast.LENGTH_LONG).show();
                return;
            }
            TunnelManager.start(getApplicationContext(), p.tunnelMode(), p.aitunKey(), p.port());
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
                Toast.makeText(this, L.t("main.batteryIgnored"), Toast.LENGTH_SHORT).show();
            } else {
                @SuppressWarnings("BatteryLife")
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg));
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, L.f("main.batteryFail", e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSettings() {
        Prefs p = new Prefs(this);
        String oldToken = p.token();
        String newToken = tokenInput.getText().toString().trim();
        String tunnelMode = ((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).isChecked()
                ? "subdomain" : "temp";
        String key = aitunKeyInput.getText().toString().trim();
        boolean modeChanged = !tunnelMode.equals(p.tunnelMode());
        boolean keyChanged = !key.equals(p.aitunKey());

        p.setAitunKey(key);
        p.setTunnelMode(tunnelMode);
        boolean tokenChanged = !newToken.isEmpty() && !newToken.equals(oldToken);
        if (tokenChanged) {
            p.setToken(newToken);
            // 热应用：不重启服务、不断隧道、Profile 全程不动，下一个请求即用新 token
            Api api = CdpService.api();
            if (api != null) api.setToken(newToken);
        }
        ProfileManager.saveProfiles();
        ProfileManager.dispatchLog("[config] settings saved"
                + (tokenChanged ? " (token hot-applied)" : ""));
        Toast.makeText(this, tokenChanged ? L.t("main.savedToken") : L.t("main.saved"),
                Toast.LENGTH_SHORT).show();

        // 只有隧道在跑且 mode/key 确实变化时才重启隧道；token 变化不再牵连隧道/服务
        TunnelManager.State ts = TunnelManager.state();
        boolean tunnelActive = ts == TunnelManager.State.ONLINE || ts == TunnelManager.State.CONNECTING;
        if (tunnelActive && (modeChanged || keyChanged)) {
            TunnelManager.stop();
            ProfileManager.dispatchLog("[aitun] restarting tunnel with new settings");
            if (tunnelMode.equals("subdomain") && key.isEmpty()) {
                // never silently restart as something else; say exactly what was skipped
                ProfileManager.dispatchLog("[aitun] restart skipped: subdomain mode without key");
                Toast.makeText(this, L.t("main.tunnelStoppedNeedKey"), Toast.LENGTH_LONG).show();
            } else {
                ui.postDelayed(() -> TunnelManager.start(getApplicationContext(), tunnelMode, key,
                        new Prefs(this).port()), 800);
            }
        }
        ui.postDelayed(this::render, 400);
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
            remote = L.t("main.tunnelConnecting");
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
        statusText.setText(up ? L.f("main.statusRunning", runningCount) : L.t("main.statusStopped"));
        statusText.setTextColor(getColor(up ? R.color.status_green : R.color.text_dim));

        // tunnel line
        TunnelManager.State ts = TunnelManager.state();
        if (ts == TunnelManager.State.ONLINE) {
            String runMode = "subdomain".equals(TunnelManager.mode()) ? L.t("main.modeSub") : L.t("main.modeTemp");
            String txt = L.f("main.tunnelOnline", TunnelManager.url(), runMode);
            // unsaved-edit divergence warning: what the user sees in the form
            // must be visibly reconciled against what is actually running
            String uiMode = ((android.widget.RadioButton) findViewById(R.id.mode_subdomain)).isChecked()
                    ? "subdomain" : "temp";
            String uiKey = aitunKeyInput.getText().toString().trim();
            boolean modeDiffers = !uiMode.equals(TunnelManager.mode());
            boolean keyDiffers = "subdomain".equals(TunnelManager.mode())
                    && !uiKey.equals(TunnelManager.keyUsed());
            if (modeDiffers || keyDiffers) {
                txt += "\n" + L.f("main.tunnelModeMismatch", runMode);
                tunnelStatus.setTextColor(getColor(R.color.dot_orange));
            } else {
                tunnelStatus.setTextColor(getColor(R.color.status_green));
            }
            tunnelStatus.setText(txt);
            btnTunnel.setText(L.t("main.stopTunnel"));
        } else if (ts == TunnelManager.State.CONNECTING) {
            String connecting = L.t("main.connecting");
            if (TunnelManager.lastError() != null) {
                connecting += " " + L.f("main.lastErr", TunnelManager.lastError());
            }
            tunnelStatus.setText(connecting);
            tunnelStatus.setTextColor(getColor(R.color.dot_orange));
            btnTunnel.setText(L.t("main.stopTunnel"));
        } else if (ts == TunnelManager.State.ERROR) {
            tunnelStatus.setText(L.f("main.tunnelError", TunnelManager.lastError()));
            tunnelStatus.setTextColor(getColor(R.color.danger));
            btnTunnel.setText(L.t("main.startTunnel"));
        } else {
            tunnelStatus.setText(L.f("main.tunnelOff", lan));
            tunnelStatus.setTextColor(getColor(R.color.text_dim));
            btnTunnel.setText(L.t("main.startTunnel"));
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
            // row layout: [0]=info [1]=show [2]=delete [3]=switch
            TextView info = (TextView) row.getChildAt(0);
            Button del = (Button) row.getChildAt(2);
            Switch sw = (Switch) row.getChildAt(3);
            String state;
            if (pr.running) {
                String extra = pr.autoStopAtMs > 0
                        ? " · " + Math.max(0, (pr.autoStopAtMs - System.currentTimeMillis()) / 60000) + "m"
                        : "";
                state = L.f("main.runnerOn", pr.slot, extra);
            } else {
                state = pr.enabled ? L.t("main.idle") : L.t("main.disabled");
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
        show.setText(L.t("main.showWebView"));
        show.setAllCaps(false);
        show.setTextSize(12);
        // SAME pattern as the 删除 button: colored text on the light card bg that
        // matches the button's own border — white text here was invisible
        // (white-on-white on the cream theme, user-reported in v1.3.3)
        show.setTextColor(getColor(R.color.heading));
        show.setBackground(getDrawable(R.drawable.btn_orange));
        show.setPadding(26, 10, 26, 10);
        show.setOnClickListener(v -> {
            if (!pr.running) {
                Toast.makeText(this, L.t("main.notRunning"), Toast.LENGTH_SHORT).show();
                return;
            }
            // Android 10+ / ColorOS: TabActivity is launched from the runner's
            // background service process — without the "display over other apps"
            // permission the system silently drops that launch (webview never shows)
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, L.t("main.overlayPerm"),
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, L.t("main.overlayRetry"), Toast.LENGTH_LONG).show();
                }
                return;
            }
            show.setEnabled(false);
            // HTTP to the runner must leave the main thread (NetworkOnMainThreadException
            // made this button silently fail with only an error toast in <= v1.3.1)
            new Thread(() -> {
                boolean ok = false;
                String errMsg = null;
                try {
                    ProfileManager.ProxyResult res = ProfileManager.show(pr, null);
                    // the runner answers HTTP 200 even for {ok:false,error:...} —
                    // only the body tells success from failure
                    org.json.JSONObject j = new org.json.JSONObject(new String(res.payload, "UTF-8"));
                    ok = res.status == 200 && j.optBoolean("ok");
                    if (!ok) {
                        String e2 = j.optString("error");
                        errMsg = e2.isEmpty() ? "HTTP " + res.status : e2;
                    }
                } catch (Exception e) {
                    errMsg = e.getMessage();
                    ProfileManager.dispatchLog("[" + pr.name + "] show failed: " + errMsg);
                }
                // ColorOS can still silently drop the activity launch even WITH the
                // overlay permission (后台弹出界面/自启动 restriction) — detect it by
                // polling the runner's /visible instead of pretending success
                boolean cameUp = false;
                if (ok) {
                    for (int i = 0; i < 8 && !cameUp; i++) {
                        try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                        try {
                            ProfileManager.ProxyResult vr = ProfileManager.visible(pr);
                            if (vr.status == 200 && new org.json.JSONObject(new String(vr.payload, "UTF-8"))
                                    .optBoolean("visible")) cameUp = true;
                        } catch (Exception ignored) {}
                    }
                }
                final boolean fOk = ok;
                final boolean fUp = cameUp;
                final String fErr = errMsg;
                ui.post(() -> {
                    show.setEnabled(true);
                    if (!fOk) {
                        Toast.makeText(this, L.f("main.showFailed", fErr), Toast.LENGTH_LONG).show();
                    } else if (!fUp) {
                        Toast.makeText(this, L.t("main.showNoPopup"), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, L.t("main.shown"), Toast.LENGTH_SHORT).show();
                    }
                    ui.postDelayed(this::render, 500);
                });
            }, "samcdp-show").start();
        });
        row.addView(show);

        Button del = new Button(this);
        del.setText(L.t("main.delete"));
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
