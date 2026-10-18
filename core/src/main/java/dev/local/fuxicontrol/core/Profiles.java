package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import java.util.UUID;

/** Named configurations live in the same atomic document as the active policy. */
public final class Profiles {
    private static JSONObject snapshot(JSONObject config) {
        JSONObject value=Policy.copy(config);
        value.remove("profiles");value.remove("activeProfile");value.remove("revision");
        return value;
    }
    public static void sync(JSONObject config) {
        if(!config.has("profiles")) {
            config.put("profiles",new JSONObject().put("default",new JSONObject().put("name","日常")));
            config.put("activeProfile","default");
        }
        config.getJSONObject("profiles").getJSONObject(config.getString("activeProfile")).put("config",snapshot(config));
    }
    public static String name(JSONObject config) {
        return config.has("profiles")?config.getJSONObject("profiles").getJSONObject(config.getString("activeProfile")).getString("name"):"日常";
    }
    public static void create(JSONObject config,String name) {
        name=name.trim();
        if(name.isEmpty() || name.length()>40) throw new IllegalArgumentException("配置名称需为 1–40 个字符");
        sync(config);
        JSONObject profiles=config.getJSONObject("profiles");
        for(String id:Json.keys(profiles)) if(profiles.getJSONObject(id).getString("name").equals(name))
            throw new IllegalArgumentException("配置名称已存在");
        String id=UUID.randomUUID().toString();
        profiles.put(id,new JSONObject().put("name",name).put("config",snapshot(config)));
        config.put("activeProfile",id);
    }
    public static JSONObject activate(JSONObject config,String id) {
        sync(config);
        JSONObject profiles=config.getJSONObject("profiles");
        JSONObject next=Policy.copy(profiles.getJSONObject(id).getJSONObject("config"));
        return next.put("profiles",profiles).put("activeProfile",id).put("revision",config.getLong("revision"));
    }
    public static void validate(JSONObject config) {
        if(!config.has("profiles")) return;
        JSONObject profiles=config.getJSONObject("profiles");
        if(!profiles.has(config.getString("activeProfile"))) throw new IllegalArgumentException("Unknown active profile");
        for(String id:Json.keys(profiles)) {
            JSONObject entry=profiles.getJSONObject(id), value=entry.getJSONObject("config");
            String name=entry.getString("name").trim();
            if(name.isEmpty() || name.length()>40 || value.has("profiles") || value.has("activeProfile"))
                throw new IllegalArgumentException("Invalid profile");
            Policy.validate(value);
        }
    }
}
