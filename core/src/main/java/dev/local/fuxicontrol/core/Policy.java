package dev.local.fuxicontrol.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Versioned desired policy. Runtime execution status is stored separately. */
public final class Policy {
    private static final Set<String> FIELDS = Set.of("autostart", "background", "cpuNice", "uclampMax",
            "memoryHighMiB", "memoryMaxMiB", "uploadBps", "downloadBps", "burstBytes", "network",
            "screenOffCleanup", "screenOffDelaySeconds", "services", "blockSplashAds", "permissions", "appOps", "protected", "resident", "cleanupOnTaskRemoved", "wakeLocks");
    public static JSONObject initial() {
        return new JSONObject().put("schema", 1).put("revision", 0).put("mode", "observe")
                .put("defaults", new JSONObject().put("autostart", "deny").put("background", "balanced")
                        .put("cpuNice", 5).put("uclampMax", 1024).put("memoryHighMiB", 0)
                        .put("memoryMaxMiB", 0).put("uploadBps", 0).put("downloadBps", 0)
                        .put("burstBytes", 65536).put("network", "allow")
                        .put("permissions", new JSONObject()).put("appOps", new JSONObject())
                        .put("screenOffCleanup","off").put("screenOffDelaySeconds",60)
                        .put("wakeLocks", new JSONArray()).put("cleanupOnTaskRemoved", false).put("resident",false))
                .put("apps", new JSONObject()).put("rules", new JSONArray()).put("pushProxy",new JSONObject());
    }
    public static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    public static void validatePatch(JSONObject patch) {
        for (String field : dev.local.fuxicontrol.core.Json.keys(patch)) {
            if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown policy field: " + field);
            switch (field) {
                case "screenOffCleanup" -> oneOf(patch.getString(field),"off","cached","force-stop");
                case "screenOffDelaySeconds" -> range(patch.getLong(field),10,86400,field);
                case "services" -> {
                    JSONObject services=patch.getJSONObject(field);
                    for(String component:Json.keys(services)) {
                        if(!component.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                            throw new IllegalArgumentException("Expected full service class name");
                        oneOf(services.getString(component),"block");
                    }
                }
                case "autostart" -> oneOf(patch.getString(field), "allow", "deny");
                case "background" -> oneOf(patch.getString(field), "normal", "balanced", "restricted");
                case "network" -> oneOf(patch.getString(field), "allow", "background-deny", "deny");
                case "cpuNice" -> range(patch.getLong(field), 0, 19, field);
                case "uclampMax" -> range(patch.getLong(field), 1, 1024, field);
                case "memoryHighMiB", "memoryMaxMiB" -> range(patch.getLong(field), 0, 1048576, field);
                case "uploadBps", "downloadBps" -> range(patch.getLong(field), 0, 1_000_000_000_000L, field);
                case "burstBytes" -> range(patch.getLong(field), 65536, 16 * 1024 * 1024, field);
                case "blockSplashAds", "protected", "cleanupOnTaskRemoved", "resident" -> patch.getBoolean(field);
                case "permissions" -> {
                    JSONObject permissions = patch.getJSONObject(field);
                    for (String name : dev.local.fuxicontrol.core.Json.keys(permissions)) {
                        if (!name.startsWith("android.permission.")) throw new IllegalArgumentException("Expected Android permission name");
                        oneOf(permissions.getString(name), "deny", "system");
                    }
                }
                case "appOps" -> {
                    JSONObject ops = patch.getJSONObject(field);
                    for (String name : dev.local.fuxicontrol.core.Json.keys(ops)) oneOf(ops.getString(name), "allow", "ignore", "deny", "foreground", "default");
                }
                case "wakeLocks" -> {
                    JSONArray tags = patch.getJSONArray(field);
                    for (int i = 0; i < tags.length(); i++) if (tags.getString(i).isEmpty())
                        throw new IllegalArgumentException("Empty WakeLock tag");
                }
            }
        }
    }
    public static void validate(JSONObject config) {
        Profiles.validate(config);
        if (config.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported schema");
        oneOf(config.getString("mode"), "observe", "enforce", "disabled");
        validatePatch(config.getJSONObject("defaults"));
        if(config.getJSONObject("defaults").optString("screenOffCleanup","off").equals("force-stop"))
            throw new IllegalArgumentException("强制停止只能逐应用启用");
        if(config.getJSONObject("defaults").has("services") && config.getJSONObject("defaults").getJSONObject("services").length()>0)
            throw new IllegalArgumentException("服务组件只能逐应用配置");
        if(config.getJSONObject("defaults").optBoolean("blockSplashAds", false))
            throw new IllegalArgumentException("开屏广告屏蔽只能逐应用启用");
        for (String name : dev.local.fuxicontrol.core.Json.keys(config.getJSONObject("apps"))) {
            packageName(name); validatePatch(config.getJSONObject("apps").getJSONObject(name));
            if(config.getJSONObject("apps").getJSONObject(name).optBoolean("blockSplashAds",false) && SplashTargets.find(name)==null)
                throw new IllegalArgumentException("当前应用没有开屏适配器："+name);
        }
        JSONObject proxies=config.optJSONObject("pushProxy");
        if(proxies!=null) for(String name:Json.keys(proxies)) {
            packageName(name);
            JSONObject p=proxies.getJSONObject(name);
            for(String key:Json.keys(p)) {
                if(!Set.of("enabled","showContent","startOnPush","skipIfRunning","sound","vibrate").contains(key))
                    throw new IllegalArgumentException("Unknown push proxy field: "+key);
                p.getBoolean(key);
            }
        }
        for (int i = 0; i < config.getJSONArray("rules").length(); i++) Rules.validate(config.getJSONArray("rules").getJSONObject(i));
    }
    public static JSONObject effective(JSONObject config, String name, boolean protectedRole) {
        JSONObject result = new JSONObject().put("screenOffCleanup","off").put("screenOffDelaySeconds",60).put("services",new JSONObject());
        for(String key:Json.keys(config.getJSONObject("defaults"))) result.put(key,config.getJSONObject("defaults").get(key));
        JSONObject sources = new JSONObject();
        for (String key : dev.local.fuxicontrol.core.Json.keys(result)) sources.put(key, "default");
        JSONObject app = config.getJSONObject("apps").optJSONObject(name);
        if (app!=null && app.has("protected")?app.getBoolean("protected"):protectedRole) {
            JSONObject protection = new JSONObject().put("autostart", "allow").put("background", "normal")
                    .put("cpuNice", 0).put("uclampMax", 1024).put("memoryHighMiB", 0).put("memoryMaxMiB", 0)
                    .put("network", "allow").put("uploadBps",0).put("downloadBps",0)
                    .put("permissions",new JSONObject()).put("appOps",new JSONObject()).put("protected", true);
            for (String key : dev.local.fuxicontrol.core.Json.keys(protection)) { result.put(key, protection.get(key)); sources.put(key, "protected-role"); }
        }
        if (app != null) for (String key : dev.local.fuxicontrol.core.Json.keys(app)) {
            result.put(key, app.get(key)); sources.put(key, "app");
        }
        result.put("sources", sources);
        return result;
    }
    public static JSONObject pushProxy(JSONObject config,String name) {
        JSONObject result=new JSONObject();
        for(String key:new String[]{"enabled","showContent","startOnPush","skipIfRunning","sound","vibrate"}) result.put(key,false);
        JSONObject proxies=config.optJSONObject("pushProxy");
        if(proxies!=null && proxies.has(name)) {
            JSONObject p=proxies.getJSONObject(name);
            for(String key:Json.keys(p)) result.put(key,p.get(key));
        }
        return result;
    }
    public static void packageName(String name) {
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("Invalid package name");
    }
    public static void oneOf(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
    private static void range(long value, long min, long max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " out of range");
    }
}
