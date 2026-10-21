package dev.local.fuxicontrol.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Persisted, per-install startup circuit breaker. All timestamps are controller supplied. */
public final class SplashHealth {
    public static final long STARTUP_MS = 60_000;
    public static final long FAILURE_WINDOW_MS = 300_000;
    public enum Exit { CRASH, ANR, OTHER }
    private final JSONObject apps;

    public SplashHealth(JSONObject saved) { apps = saved; }
    public JSONObject saved() { return Policy.copy(apps); }

    private JSONObject entry(String name, String key) {
        JSONObject value = apps.optJSONObject(name);
        if (value == null || !key.equals(value.getString("key"))) {
            value = new JSONObject().put("key", key).put("suspended", false)
                    .put("reason", "").put("failures", new JSONArray());
            apps.put(name, value);
        }
        return value;
    }

    public JSONObject status(String name, String key) {
        JSONObject value = entry(name, key);
        return new JSONObject().put("suspended", value.getBoolean("suspended"))
                .put("suspendReason", value.getString("reason"));
    }

    public JSONObject session(String name, String key) {
        JSONObject session = entry(name, key).optJSONObject("session");
        return session == null ? null : Policy.copy(session);
    }

    public void begin(String name, String key, int pid, String boot, long now) {
        JSONObject value = entry(name, key), previous = value.optJSONObject("session");
        if (previous != null && previous.getInt("pid") == pid && previous.getString("boot").equals(boot)) return;
        value.put("session", new JSONObject().put("pid", pid).put("boot", boot)
                .put("started", now).put("armed", false).put("settled", false));
    }

    public void arm(String name, String key, int pid, String boot, long now) {
        begin(name, key, pid, boot, now);
        JSONObject value = entry(name, key);
        if (!value.getBoolean("suspended")) value.getJSONObject("session").put("armed", true);
    }

    public void healthy(String name, String key, int pid, String boot, long now) {
        JSONObject value = entry(name, key), session = value.optJSONObject("session");
        if (session != null && session.getInt("pid") == pid && session.getString("boot").equals(boot)
                && session.getBoolean("armed") && now - session.getLong("started") >= STARTUP_MS) {
            session.put("settled", true);
            value.put("failures", new JSONArray());
        }
    }

    public void disarm(String name, String key, int pid) {
        JSONObject session = entry(name, key).optJSONObject("session");
        if (session != null && session.getInt("pid") == pid) session.put("armed", false);
    }

    public void exit(String name, String key, int pid, String boot, long at, Exit reason) {
        JSONObject value = entry(name, key), session = value.optJSONObject("session");
        if (session == null || session.getInt("pid") != pid || !session.getString("boot").equals(boot)
                || session.getBoolean("settled") || at < session.getLong("started")) return;
        session.put("settled", true);
        if (!session.getBoolean("armed") || reason == Exit.OTHER || at - session.getLong("started") > STARTUP_MS) return;
        JSONArray recent = new JSONArray(), old = value.getJSONArray("failures");
        for (int i = 0; i < old.length(); i++) {
            long failure = old.getLong(i);
            if (at - failure <= FAILURE_WINDOW_MS) recent.put(failure);
        }
        recent.put(at);
        value.put("failures", recent);
        if (recent.length() >= 2) value.put("suspended", true).put("reason",
                "5 分钟内连续两次启动异常（" + (reason == Exit.ANR ? "ANR" : "崩溃") + "），疑似开屏兼容问题");
    }

    public void retry(String name, String key) {
        apps.remove(name);
        entry(name, key);
    }
}
