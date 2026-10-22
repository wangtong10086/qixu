package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import java.util.Set;

/** Process budgets refine the UID budget; they never duplicate it. */
public final class ProcessPolicy {
    public static final Set<String> FIELDS=Set.of("cpuAffinity","cpuQuota","cpuNice","uclampMax","memoryHighMiB","memoryMaxMiB","uploadBps","downloadBps","burstBytes","network");
    public static void validate(JSONObject rules) {
        for(String name:Json.keys(rules)) {
            if(name.isBlank() || name.length()>256 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Expected full process name");
            JSONObject rule=rules.getJSONObject(name);
            for(String key:Json.keys(rule)) {
                if(key.equals("threads")){AffinityPolicy.threads(rule.getJSONArray(key));continue;}
                if(key.equals("exemptActiveTasks")) {rule.getBoolean(key);continue;}
                Policy.oneOf(key,"foreground","background");
                JSONObject values=rule.getJSONObject(key);
                for(String field:Json.keys(values)) if(!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown process resource: "+field);
                JSONObject resources=Policy.copy(values);resources.remove("cpuAffinity");Policy.validatePatch(resources);
                if(values.has("cpuAffinity"))AffinityPolicy.selector(values.getString("cpuAffinity"));
                if(values.optLong("memoryMaxMiB",0)>0 && values.optLong("memoryHighMiB",0)>values.getLong("memoryMaxMiB"))
                    throw new IllegalArgumentException("内存回收阈值不能超过硬上限");
            }
        }
    }
    public static JSONObject effective(JSONObject app,JSONObject rule,boolean foreground,boolean activity) {
        JSONObject value=new JSONObject().put("cpuQuota",CpuQuota.defaults()).put("cpuNice",foreground?0:app.getInt("cpuNice"))
                .put("uclampMax",foreground?1024:app.getInt("uclampMax"))
                .put("memoryHighMiB",0).put("memoryMaxMiB",0).put("uploadBps",0).put("downloadBps",0)
                .put("burstBytes",app.getInt("burstBytes")).put("network","allow");
        JSONObject stage=rule.optJSONObject(foreground?"foreground":"background");
        if(stage!=null) for(String field:Json.keys(stage)) value.put(field,stage.get(field));
        boolean exempt=app.optBoolean("protected",false) || (activity && rule.optBoolean("exemptActiveTasks",true));
        if(exempt) value.put("cpuQuota",CpuQuota.defaults()).put("cpuNice",0).put("uclampMax",1024).put("memoryHighMiB",0).put("memoryMaxMiB",0);
        return value.put("stage",foreground?"foreground":"background").put("exempt",exempt);
    }
}
