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
import android.widget.EditText;
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
 * TabActivity (TabActivity1..6, singleTask), and a toolbar pill in the top bar
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
    private EditText urlBox;
    private AlertDialog switcherDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v1.3.8: the toolbar is REAL LAYOUT (LinearLayout rows), not an overlay —
        // the WebView lives in the weight=1 content area BELOW it, so nothing the
        // site renders is ever covered by our controls.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        setContentView(root);

        float den = getResources().getDisplayMetrics().density;
        int padH = Math.round(14 * den), padV = Math.round(8 * den);
        int rowPad = Math.round(8 * den);

        // toolbar: row1 [address bar][前往][←][➕]   row2 [≡ 标签] …spacer… [⟳][✕]
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackgroundColor(0xFF1B1B1B);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(rowPad, rowPad, rowPad, Math.round(4 * den));
        toolbar.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(rowPad, Math.round(4 * den), rowPad, rowPad);
        toolbar.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // hairline separating toolbar from page content
        android.view.View hairline = new android.view.View(this);
        hairline.setBackgroundColor(0x33FFFFFF);
        toolbar.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // content BELOW the toolbar — fills all remaining space (no overlap)
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // switcher pill (profile name + menu glyph), lives on row2 of the toolbar
        pill = new TextView(this);
        pill.setTextSize(12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(0xFFFFFFFF);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA000000);
        bg.setCornerRadius(20 * den);
        pill.setBackground(bg);
        pill.setOnClickListener(v -> openSwitcher());
        String pn = TabEngine.profileName();
        pill.setText((pn == null ? "" : pn + "  ") + "≡ 标签");

        // row2: [pill] …spacer… [⟳][✕]
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2.addView(pill, plp);
        android.widget.Space flex = new android.widget.Space(this);
        row2.addView(flex, new LinearLayout.LayoutParams(0, 1, 1f));

        // manual address entry: shows the visible tab's current URL, editable
        urlBox = new EditText(this);
        urlBox.setTextSize(12);
        urlBox.setTextColor(0xFFFFFFFF);
        urlBox.setHintTextColor(0x88FFFFFF);
        urlBox.setHint("输入网址，如 example.com");
        urlBox.setSingleLine(true);
        urlBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        urlBox.setBackgroundResource(0);
        urlBox.setPadding(Math.round(14 * den), Math.round(6 * den), Math.round(14 * den), Math.round(6 * den));
        urlBox.setHorizontalFadingEdgeEnabled(true);
        GradientDrawable ubg = new GradientDrawable();
        ubg.setColor(0xAA000000);
        ubg.setCornerRadius(20 * den);
        urlBox.setBackground(ubg);
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                goUrl();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ulp.rightMargin = Math.round(8 * den);
        row1.addView(urlBox, ulp);

        // 前往: load the typed address in the visible tab
        TextView goBtn = new TextView(this);
        goBtn.setTextSize(12);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setTextColor(0xFFFFFFFF);
        goBtn.setSingleLine(true);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(Math.round(12 * den), padV, Math.round(12 * den), padV);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(0xAA000000);
        gbg.setCornerRadius(20 * den);
        goBtn.setBackground(gbg);
        goBtn.setText("前往");
        goBtn.setOnClickListener(v -> goUrl());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.rightMargin = Math.round(8 * den);
        row1.addView(goBtn, glp);

        // ←: history-back on the visible tab (v1.3.8)
        TextView backBtn = new TextView(this);
        backBtn.setTextSize(14);
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setTextColor(0xFFFFFFFF);
        backBtn.setSingleLine(true);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(Math.round(12 * den), padV, Math.round(12 * den), padV);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(0xAA000000);
        bbg.setCornerRadius(20 * den);
        backBtn.setBackground(bbg);
        backBtn.setText("←");
        backBtn.setOnClickListener(v -> {
            String r = TabEngine.goBackVisible();
            if (r != null && r.contains("\"ok\":false")) Toast2("已是第一页");
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Math.round(8 * den);
        row1.addView(backBtn, blp);

        // ➕: open a fresh start tab and show it immediately (v1.3.8)
        TextView plusBtn = new TextView(this);
        plusBtn.setTextSize(16);
        plusBtn.setTypeface(Typeface.DEFAULT_BOLD);
        plusBtn.setTextColor(0xFFFFFFFF);
        plusBtn.setSingleLine(true);
        plusBtn.setGravity(Gravity.CENTER);
        plusBtn.setPadding(Math.round(12 * den), padV, Math.round(12 * den), padV);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xAA000000);
        pbg.setCornerRadius(20 * den);
        plusBtn.setBackground(pbg);
        plusBtn.setText("+");
        plusBtn.setOnClickListener(v -> {
            String r = TabEngine.newTabVisible(this);
            if (r == null || !r.contains("\"ok\":true")) Toast2("新开标签失败");
            else updateUrlBox();
        });
        row1.addView(plusBtn);

        // ⟳: reload the visible page (recovers stuck/blank pages)
        TextView refreshBtn = new TextView(this);
        refreshBtn.setTextSize(12);
        refreshBtn.setTypeface(Typeface.DEFAULT_BOLD);
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setSingleLine(true);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(padH, padV, padH, padV);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xAA000000);
        rbg.setCornerRadius(20 * den);
        refreshBtn.setBackground(rbg);
        refreshBtn.setText("⟳");
        refreshBtn.setOnClickListener(v -> {
            String r = TabEngine.reloadVisible();
            if (r != null && r.contains("\"ok\":false")) Toast2("没有可刷新的页面");
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.rightMargin = Math.round(8 * den);
        row2.addView(refreshBtn, rlp);

        // ✕: detach the WebView straight back to headless
        TextView hideBtn = new TextView(this);
        hideBtn.setTextSize(12);
        hideBtn.setTypeface(Typeface.DEFAULT_BOLD);
        hideBtn.setTextColor(0xFFFFFFFF);
        hideBtn.setSingleLine(true);
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setPadding(padH, padV, padH, padV);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(0xAA000000);
        hbg.setCornerRadius(20 * den);
        hideBtn.setBackground(hbg);
        hideBtn.setText("✕");
        hideBtn.setOnClickListener(v -> {
            TabEngine.hide(); // finishes the host itself, WebView back to headless
            finish();
        });
        row2.addView(hideBtn);

        // keep the keyboard closed on entry — the address bar must not steal focus
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        String want = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        String res = TabEngine.attachRequested(this, content, want);
        if (res != null) {
            // nothing to show (no tabs / engine down) — brief message then close
            TextView tv = new TextView(this);
            tv.setText("samcdp: nothing to show\n(no running tab in this profile)");
            tv.setTextColor(0xFFE0E0E0);
            tv.setGravity(Gravity.CENTER);
            content.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tv.postDelayed(this::finish, 1500);
        } else {
            updateUrlBox();
            // address box follows the visible tab's URL (page navigations, redirects)
            TabEngine.setPageUrlListener((tabId, url) -> {
                String vt = TabEngine.visibleTabId();
                if (tabId != null && tabId.equals(vt)) updateUrlBox();
            });
        }
    }

    /** Reflect the visible tab's current URL in the address bar (unless being edited). */
    private void updateUrlBox() {
        if (urlBox == null || urlBox.hasFocus()) return;
        String u = TabEngine.visibleTabUrl();
        if (u != null && !u.equals(urlBox.getText() == null ? "" : urlBox.getText().toString())) {
            urlBox.setText(u);
        }
    }

    /** 前往 / keyboard Go: load the typed address in the visible tab. */
    private void goUrl() {
        if (urlBox == null) return;
        String raw = urlBox.getText() == null ? "" : urlBox.getText().toString();
        if (raw.trim().isEmpty()) {
            Toast2("请输入网址");
            return;
        }
        String r = TabEngine.navigateVisible(raw);
        if (r == null || !r.contains("\"ok\":true")) {
            Toast2("打开失败：没有显示中的页面");
            return;
        }
        hideKeyboard();
        String u = TabEngine.visibleTabUrl();
        if (u != null) urlBox.setText(u);
    }

    private void hideKeyboard() {
        Object svc = getSystemService(INPUT_METHOD_SERVICE);
        if (svc instanceof android.view.inputmethod.InputMethodManager && urlBox != null) {
            ((android.view.inputmethod.InputMethodManager) svc)
                    .hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        }
        if (urlBox != null) urlBox.clearFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (content == null) return;
        String want = intent != null ? intent.getStringExtra("tab") : null;
        TabEngine.attachRequested(this, content, want);
        updateUrlBox();
    }

    @Override
    public void onBackPressed() {
        // back key = detach & return to headless (browser back is handled by the page itself)
        finish();
    }

    @Override
    protected void onDestroy() {
        TabEngine.setPageUrlListener(null);
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
                updateUrlBox();
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

    /**
     * v1.3.8: per-tab ✕ in the switcher — close a tab of the OWN profile directly
     * (TabEngine.closeTab), a tab of ANOTHER profile via its runner API
     * (POST /tabs/<id>/close). The list re-probes afterwards; if the closed tab
     * was the visible one the engine already handed the screen to another tab
     * or dropped to headless (activity finished).
     */
    private void closeEntry(Entry e) {
        if (e.mine) {
            String r = TabEngine.closeTab(e.tabId);
            boolean ok = r != null && r.contains("\"ok\":true");
            if (!ok) {
                Toast2("关闭失败");
                return;
            }
            if (isFinishing()) return; // visible tab closed → engine went headless
            Toast2("已关闭标签页");
            reopenSwitcher();
        } else {
            Toast2("关闭中…");
            final int slot = e.slot;
            final String tabId = e.tabId;
            new Thread(() -> {
                String r = post("http://127.0.0.1:" + (9400 + slot) + "/tabs/" + tabId + "/close", 1500);
                MAIN.post(() -> {
                    Toast2(r != null && r.contains("\"ok\":true") ? "已关闭标签页" : "关闭失败（对方 profile 未响应）");
                    reopenSwitcher();
                });
            }, "samcdp-tab-close").start();
        }
    }

    private void reopenSwitcher() {
        if (switcherDialog != null) {
            switcherDialog.dismiss();
            switcherDialog = null;
        }
        openSwitcher();
    }

    private int dip(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String post(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
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
            // v1.3.8: horizontal row — [tab info (weight 1)][✕ close button]
            LinearLayout row = new LinearLayout(TabActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(TabActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            int pad = dip(14);
            info.setPadding(dip(20), pad, dip(8), pad);
            row.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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

                // per-tab ✕ close (v1.3.8)
                TextView cb = new TextView(TabActivity.this);
                cb.setTextSize(14);
                cb.setTypeface(Typeface.DEFAULT_BOLD);
                cb.setTextColor(0xFFB33A3A);
                cb.setSingleLine(true);
                cb.setGravity(Gravity.CENTER);
                cb.setPadding(dip(16), pad, dip(22), pad);
                cb.setText("✕");
                cb.setOnClickListener(v -> closeEntry(e));
                row.addView(cb);
            }
            info.addView(top);
            info.addView(sub);

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
