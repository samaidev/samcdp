package cc.samaidev.samcdp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Frontend host: shows a profile's WebView FULLSCREEN so a human can step in
 * (CAPTCHA, login, 2FA, consent...) — with a MULTI-PROFILE SWITCHER.
 *
 * Every running profile lives in its own process (:cdp1..:cdp6, isolated cookie
 * jars), so WebViews cannot cross processes. Instead each profile gets its own
 * TabActivity (TabActivity1..6, singleTask), and a floating pill in the corner
 * opens a switcher that lists EVERY running profile's tabs (probed over the
 * localhost runner APIs on 9401..9406):
 *
 *   - tap a tab of the SAME profile  -> TabEngine.switchTab(): WebView swapped
 *     inside this activity, no restart, state preserved
 *   - tap a tab of ANOTHER profile   -> that profile's TabActivityN comes to
 *     front (singleTask onNewIntent re-targets the WebView)
 *   - tap "hide"                     -> finish() -> WebView back to headless
 *
 * Backgrounded TabActivities stay alive with their WebView attached, so
 * switching back is instant and background JS keeps running.
 */
public class TabActivity extends Activity {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private TextView pill;
    private AlertDialog switcherDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101010);
        setContentView(root);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // floating switcher pill (profile name + switch glyph), top-left overlay
        pill = new TextView(this);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(0xFFFFFFFF);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(28, 14, 28, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA000000);
        bg.setCornerRadius(40);
        pill.setBackground(bg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.START;
        plp.leftMargin = 24;
        plp.topMargin = 24;
        pill.setLayoutParams(plp);
        pill.setOnClickListener(v -> openSwitcher());
        String pn = TabEngine.profileName();
        pill.setText((pn == null ? "" : pn + "  ") + "≡");
        root.addView(pill);

        String want = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        String res = TabEngine.attachRequested(this, content, want);
        if (res != null) {
            // nothing to show (no tabs / engine down) — brief message then close
            TextView tv = new TextView(this);
            tv.setText("samcdp: nothing to show\n(no running tab in this profile)");
            tv.setTextColor(0xFFE0E0E0);
            tv.setGravity(Gravity.CENTER);
            root.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tv.postDelayed(this::finish, 1500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (content == null) return;
        String want = intent != null ? intent.getStringExtra("tab") : null;
        TabEngine.attachRequested(this, content, want);
    }

    @Override
    public void onBackPressed() {
        // back key = detach & return to headless (browser back is handled by the page itself)
        finish();
    }

    @Override
    protected void onDestroy() {
        TabEngine.detach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ switcher

    private static class Entry {
        String profile;
        int slot;
        String tabId;
        String title;
        String url;
        boolean visible;
        boolean mine;
        boolean action;      // bottom action row (hide current / close current)
        String actionLabel;
    }

    private void openSwitcher() {
        if (switcherDialog != null) return;
        final String self = TabEngine.profileName();
        final List<Entry> entries = new ArrayList<>();
        // probe every runner slot's localhost tab API in the background
        new Thread(() -> {
            for (int slot = 1; slot <= ProfileManager.MAX_PROFILES; slot++) {
                JSONObject info = getJson("http://127.0.0.1:" + (9400 + slot) + "/info", 350);
                if (info == null || !info.optBoolean("ok")) continue;
                String profile = info.optString("profile", "profile-" + slot);
                JSONObject vis = getJson("http://127.0.0.1:" + (9400 + slot) + "/visible", 350);
                String visTab = vis == null ? null : vis.optString("tab", null);
                boolean visOn = vis != null && vis.optBoolean("visible");
                JSONObject tabs = getJson("http://127.0.0.1:" + (9400 + slot) + "/tabs", 500);
                JSONArray arr = tabs == null ? null : tabs.optJSONArray("tabs");
                if (arr == null || arr.length() == 0) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    Entry e = new Entry();
                    e.profile = profile;
                    e.slot = slot;
                    e.tabId = t.optString("id");
                    e.title = t.optString("title", "");
                    e.url = t.optString("url", "");
                    e.visible = visOn && t.optString("id").equals(visTab);
                    e.mine = self != null && self.equals(profile);
                    entries.add(e);
                }
            }
            MAIN.post(() -> showSwitcherDialog(entries));
        }, "samcdp-switcher-probe").start();
    }

    private void showSwitcherDialog(List<Entry> probed) {
        List<Entry> entries = new ArrayList<>(probed);

        // bottom actions: hide / close the CURRENT profile's visible tab
        Entry hide = new Entry();
        hide.action = true;
        hide.actionLabel = "⌄  隐藏当前 WebView（回到无头模式）";
        entries.add(hide);
        Entry close = new Entry();
        close.action = true;
        close.actionLabel = "✕  关闭当前显示的标签页";
        entries.add(close);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        if (probed.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有找到运行中的标签页");
            empty.setTextColor(0xFF888888);
            empty.setPadding(48, 40, 48, 40);
            box.addView(empty);
        }

        // header
        if (!probed.isEmpty()) {
            TextView head = new TextView(this);
            head.setText("切换 WebView · 共 " + probed.size() + " 个标签");
            head.setTextColor(0xFF888888);
            head.setTextSize(11);
            head.setPadding(48, 36, 48, 12);
            box.addView(head);
        }

        final EntryAdapter adapter = new EntryAdapter(entries);
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(adapter);
        lv.setDivider(null);
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            if (switcherDialog != null) {
                switcherDialog.dismiss();
                switcherDialog = null;
            }
            Entry e = entries.get(pos);
            if (e.action) {
                if (e == close) {
                    String vis = TabEngine.visibleTabId();
                    if (vis != null) {
                        TabEngine.closeTab(vis);
                        finish(); // onDestroy -> detach -> headless
                    } else {
                        Toast2("当前没有显示中的标签页");
                    }
                } else {
                    TabEngine.hide();
                    finish();
                }
            } else if (e.mine) {
                TabEngine.switchTab(this, e.tabId);
            } else {
                try {
                    Intent i = new Intent(this, Class.forName(
                            "cc.samaidev.samcdp.TabActivity" + e.slot));
                    i.putExtra("tab", e.tabId);
                    startActivity(i);
                } catch (Exception ex) {
                    Toast2("切换失败: " + ex.getMessage());
                }
            }
        });
        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dip(340)));

        switcherDialog = new AlertDialog.Builder(this)
                .setTitle("samcdp 前端 · " + (TabEngine.profileName() == null ? "" : TabEngine.profileName()))
                .setView(box)
                .setNegativeButton("关闭", (d, w) -> switcherDialog = null)
                .setOnDismissListener(d -> {
                    if (switcherDialog == d) switcherDialog = null;
                })
                .show();
    }

    private void Toast2(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dip(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static JSONObject getJson(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private class EntryAdapter extends BaseAdapter {
        private final List<Entry> items;

        EntryAdapter(List<Entry> items) { this.items = items; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
            Entry e = items.get(position);
            LinearLayout row = new LinearLayout(TabActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            int pad = dip(14);
            row.setPadding(dip(20), pad, dip(20), pad);

            TextView top = new TextView(TabActivity.this);
            top.setTextSize(14);
            top.setSingleLine(true);
            TextView sub = new TextView(TabActivity.this);
            sub.setTextSize(11);
            sub.setSingleLine(true);

            if (e.action) {
                top.setText(e.actionLabel);
                top.setTextColor(0xFFD2610F);
                top.setTypeface(Typeface.DEFAULT_BOLD);
                sub.setText("");
                sub.setVisibility(android.view.View.GONE);
            } else {
                String mark = e.visible ? "●  " : "      ";
                top.setText(mark + (e.title == null || e.title.isEmpty() ? e.url : e.title));
                top.setTextColor(e.visible ? 0xFF2F9E44 : 0xFF222222);
                if (e.visible) top.setTypeface(Typeface.DEFAULT_BOLD);
                String host = e.url;
                try { Uri u = Uri.parse(e.url); host = u.getHost() == null ? e.url : u.getHost(); } catch (Exception ignored) {}
                sub.setText("[" + e.profile + (e.mine ? " · 当前" : "") + "]  " + host);
                sub.setTextColor(e.mine ? 0xFFD2610F : 0xFF888888);
            }
            row.addView(top);
            row.addView(sub);

            if (position < items.size() - 1) {
                android.view.View line = new android.view.View(TabActivity.this);
                line.setBackgroundColor(0x11000000);
                row.addView(line, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
            return row;
        }
    }
}
