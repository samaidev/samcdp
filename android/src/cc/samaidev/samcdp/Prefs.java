package cc.samaidev.samcdp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;

/** SharedPreferences-backed config, mirroring the desktop config.json shape. */
public class Prefs {
    private static final String FILE = "cdpclient";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String token() {
        String t = sp.getString("auth_token", null);
        if (t == null || t.isEmpty()) {
            t = randomToken();
            sp.edit().putString("auth_token", t).apply();
        }
        return t;
    }

    public void setToken(String t) {
        sp.edit().putString("auth_token", t).apply();
    }

    public int port() {
        return sp.getInt("port", 9334);
    }

    public void setPort(int p) {
        sp.edit().putInt("port", p).apply();
    }

    /** profiles: [{"name":"agent-1","port":9334,"enabled":true}] */
    public JSONArray profilesJson() {
        String raw = sp.getString("profiles", null);
        if (raw == null) {
            JSONArray arr = new JSONArray();
            try {
                JSONObject p = new JSONObject();
                p.put("name", "agent-1");
                p.put("port", 9334);
                p.put("enabled", true);
                arr.put(p);
            } catch (Exception ignored) {
            }
            sp.edit().putString("profiles", arr.toString()).apply();
            return arr;
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void saveProfiles(JSONArray arr) {
        sp.edit().putString("profiles", arr.toString()).apply();
    }

    /** Persisted cookie jar per profile: {"<profile>": [{"domain":"example.com","cookie":"k=v; k2=v2"}]} */
    public JSONObject cookieStore() {
        try {
            return new JSONObject(sp.getString("cookies", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void saveCookieStore(JSONObject store) {
        sp.edit().putString("cookies", store.toString()).apply();
    }

    public static String randomToken() {
        String alphabet = "abcdef0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }
}
