package cc.samaidev.samcdp;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Hosts the profile's WebView FULLSCREEN so a human can step in manually
 * (CAPTCHA, login, 2FA, consent screens...). Lives in the SAME runner
 * process as the profile's TabEngine (:cdpN), so attaching the existing
 * WebView costs nothing — no state loss, no cookie change.
 *
 * Lifecycle:
 *   POST /api/profiles/<name>/show?tab=<id>   -> TabEngine.showTab() starts this activity
 *   (human interacts with the real rendered page)
 *   POST /api/profiles/<name>/hide  / back key -> finish() -> TabEngine.detach()
 *                                                -> WebView back to headless 412x915
 *
 * One concrete subclass per slot (TabActivity1..6) so each can be pinned to
 * its runner process in the manifest — same pattern as RunnerService1..6.
 */
public class TabActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101010);
        setContentView(root);

        String want = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        String res = TabEngine.attachRequested(this, root, want);
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
    public void onBackPressed() {
        // back key = detach & return to headless (browser back is handled by the page itself)
        finish();
    }

    @Override
    protected void onDestroy() {
        TabEngine.detach();
        super.onDestroy();
    }
}
