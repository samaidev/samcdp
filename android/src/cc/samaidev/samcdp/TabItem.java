package cc.samaidev.samcdp;

import android.webkit.WebView;

/** One automated tab = one headless WebView. Mirrors a Chrome CDP target. */
public class TabItem {
    public final String id;
    public final Profile profile;
    public volatile String url;
    public volatile String title = "";
    public volatile long openedAtMs;
    public WebView webView; // main-thread only

    public TabItem(String id, Profile profile, String url) {
        this.id = id;
        this.profile = profile;
        this.url = url;
        this.openedAtMs = System.currentTimeMillis();
    }
}
