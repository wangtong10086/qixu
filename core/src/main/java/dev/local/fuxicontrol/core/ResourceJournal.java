package dev.local.fuxicontrol.core;

import org.json.JSONObject;

/** Task scheduling and cgroup values are recreated at boot; policy is not. */
public final class ResourceJournal {
    public static JSONObject entries(JSONObject saved, String bootId) {
        // Upgrade the existing unwrapped journal without losing same-boot restoration data.
        if (!saved.has("bootId")) return saved;
        return saved.getString("bootId").equals(bootId)
                ? saved.getJSONObject("entries") : new JSONObject();
    }
}
