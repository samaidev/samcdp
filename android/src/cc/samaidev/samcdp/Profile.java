package cc.samaidev.samcdp;

import org.json.JSONObject;

/** Desktop-parity profile model: {"name","port","enabled"} + runtime state. */
public class Profile {
    public final String name;
    public int port;
    public boolean enabled;

    public volatile boolean running = false;
    public volatile long startedAtMs = 0;
    public volatile long durationMs = 0; // 0 = no auto stop
    public volatile long autoStopAtMs = 0;

    public Profile(String name, int port, boolean enabled) {
        this.name = name;
        this.port = port;
        this.enabled = enabled;
    }

    public JSONObject toJson(int tabsCount) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("port", port);
            o.put("enabled", enabled);
            o.put("running", running);
            o.put("tabs", tabsCount);
            if (running && autoStopAtMs > 0) {
                long left = Math.max(0, autoStopAtMs - System.currentTimeMillis());
                o.put("duration_left_s", left / 1000);
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
