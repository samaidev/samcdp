package cc.samaidev.samcdp;

import org.json.JSONObject;

/** Desktop-parity profile model: {"name","port","enabled"} + runtime state. */
public class Profile {
    public final String name;
    public int port;
    public boolean enabled;

    /** v1.4.1: persisted desired-running flag. Set when a profile is started
     *  permanently (phone switch / duration=0); the app re-activates every
     *  autoStart profile on the next launch so users never re-tap switches. */
    public volatile boolean autoStart = false;

    public volatile boolean running = false;
    public volatile int slot = 0;          // runner process slot 1..6, 0 = stopped
    public volatile long startedAtMs = 0;
    public volatile long autoStopAtMs = 0;
    public volatile int durationMin = 0;

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
            o.put("auto_start", autoStart);
            o.put("running", running);
            o.put("slot", slot);
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
