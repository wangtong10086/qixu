package dev.local.fuxicontrol.core;

import org.json.JSONObject;

/** Typed automation, deliberately independent of Thanox's expression runtime. */
public final class Rules {
    public static void validate(JSONObject rule) {
        rule.getString("id"); rule.getBoolean("enabled");
        Policy.oneOf(rule.getString("event"), "foreground-left", "push-arrived");
        Policy.packageName(rule.getString("package"));
        Policy.oneOf(rule.getString("action"), "record", "kill-cached");
        long delay = rule.getLong("delayMs");
        if (delay < 0 || delay > 86_400_000) throw new IllegalArgumentException("Rule delay out of range");
    }
    public static boolean matches(JSONObject rule, String event, String name) {
        return rule.getBoolean("enabled") && event.equals(rule.getString("event")) && name.equals(rule.getString("package"));
    }
}
