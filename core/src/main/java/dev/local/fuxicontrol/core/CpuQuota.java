package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import java.util.Set;

/** CFS bandwidth is aggregate CPU time, not a percentage of all device cores. */
public final class CpuQuota {
    public static JSONObject defaults() {return new JSONObject().put("periodUs",100000).put("foregroundUs",0).put("backgroundUs",0);}
    public static void validate(JSONObject value) {
        for(String key:Json.keys(value)) if(!Set.of("periodUs","foregroundUs","backgroundUs","allowProtected").contains(key))
            throw new IllegalArgumentException("Unknown CPU quota field: "+key);
        if(value.has("allowProtected")) value.getBoolean("allowProtected");
        long period=Long.parseLong(value.get("periodUs").toString());
        if(period<1000 || period>1000000) throw new IllegalArgumentException("CPU period must be 1–1000 ms");
        for(String phase: new String[]{"foregroundUs","backgroundUs"}) {
            long quota=Long.parseLong(value.get(phase).toString());
            if(quota!=0 && (quota<1000 || quota>period*256)) throw new IllegalArgumentException("CPU quota must be off or 1 ms–256 cores");
        }
    }
    public static long budget(JSONObject value,boolean foreground,boolean exempt) {
        return exempt?0:value.getLong(foreground?"foregroundUs":"backgroundUs");
    }
    public static double cores(JSONObject value,boolean foreground) {
        return (double)budget(value,foreground,false)/Long.parseLong(value.get("periodUs").toString());
    }
    public static void validateSharedUid(JSONObject first,JSONObject next) {
        if(first.optBoolean("allowProtected",false)!=next.optBoolean("allowProtected",false) || first.getLong("periodUs")!=next.getLong("periodUs") || first.getLong("foregroundUs")!=next.getLong("foregroundUs")
                || first.getLong("backgroundUs")!=next.getLong("backgroundUs"))
            throw new IllegalArgumentException("共享 UID 的应用必须使用相同 CPU 配额");
    }
}
