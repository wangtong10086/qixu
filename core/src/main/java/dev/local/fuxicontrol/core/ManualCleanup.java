package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import java.util.Set;

public final class ManualCleanup {
    public static JSONObject defaults() {return new JSONObject().put("mode","cached").put("backgroundSeconds",60).put("cpuPercent",0).put("rssMiB",0);}
    public static void validate(JSONObject value) {
        for(String key:Json.keys(value)) if(!Set.of("mode","backgroundSeconds","cpuPercent","rssMiB").contains(key))
            throw new IllegalArgumentException("Unknown cleanup field: "+key);
        Policy.oneOf(value.getString("mode"),"off","cached","force-stop");
        if(Long.parseLong(value.get("backgroundSeconds").toString())<0 || Long.parseLong(value.get("backgroundSeconds").toString())>604800) throw new IllegalArgumentException("Invalid background duration");
        double cpu=value.getDouble("cpuPercent");
        if(!Double.isFinite(cpu) || cpu<0 || cpu>100) throw new IllegalArgumentException("CPU must be 0–100% of device capacity");
        if(Long.parseLong(value.get("rssMiB").toString())<0 || Long.parseLong(value.get("rssMiB").toString())>1048576) throw new IllegalArgumentException("Invalid memory threshold");
    }
    public static String skip(JSONObject rule,long backgroundMs,Double cpu30s,Long rssBytes,boolean fresh) {
        if(rule.getString("mode").equals("off")) return "规则关闭";
        if(backgroundMs<rule.getLong("backgroundSeconds")*1000) return "后台时长不足";
        if(rule.getDouble("cpuPercent")>0 && (!fresh || cpu30s==null)) return "缺少连续 30 秒 CPU 样本";
        if(rule.getDouble("cpuPercent")>0 && cpu30s<rule.getDouble("cpuPercent")) return "CPU 未达到阈值";
        if(rule.getLong("rssMiB")>0 && (!fresh || rssBytes==null)) return "内存样本不可用";
        if(rule.getLong("rssMiB")>0 && rssBytes<rule.getLong("rssMiB")*1048576) return "内存未达到阈值";
        return "";
    }
}
