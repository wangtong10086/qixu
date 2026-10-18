package dev.local.fuxicontrol.service;

import dev.local.fuxicontrol.core.Policy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

public final class Migration {
    private static org.w3c.dom.Document xml(Path path) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        // Android's DOM factory lacks the Xerces disallow-doctype feature.
        // Exports are UTF-8; reject DTDs before handing text to the parser.
        String text=Text.read(path);
        if(text.contains("<!DOCTYPE")) throw new IllegalArgumentException("DTDs are not allowed in migration XML");
        var builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)-> {throw new org.xml.sax.SAXException("External entities are not allowed");});
        return builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));
    }
    private static JSONArray strings(Path path) throws Exception {
        JSONArray result=new JSONArray();
        var nodes=xml(path).getElementsByTagName("string");
        for(int i=0;i<nodes.getLength();i++) result.put(nodes.item(i).getTextContent());
        return result;
    }
    private static JSONObject app(JSONObject config,String name) throws org.json.JSONException {
        JSONObject apps=config.getJSONObject("apps");
        if(!apps.has(name)) apps.put(name,new JSONObject());
        return apps.getJSONObject(name);
    }
    public static JSONObject convert(Path directory) throws Exception {
        JSONObject manifest=new JSONObject(Text.read(directory.resolve("manifest.json")));
        for(String file:dev.local.fuxicontrol.core.Json.keys(manifest.getJSONObject("sha256"))) {
            if(!file.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("Unsafe migration file name");
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(directory.resolve(file))));
            if(!digest.equals(manifest.getJSONObject("sha256").getString(file))) throw new IllegalArgumentException("Backup checksum mismatch: "+file);
        }
        JSONObject result=Policy.initial();
        var settings=xml(directory.resolve("thanos.xml")).getElementsByTagName("setting");
        java.util.Map<String,String> values=new java.util.HashMap<>();
        for(int i=0;i<settings.getLength();i++) {
            Element entry=(Element)settings.item(i);values.put(entry.getAttribute("name"),entry.getAttribute("value"));
        }
        for(String key:new String[]{"PREF_CLEAN_UP_ON_TASK_REMOVED","PREF_SMART_STANDBY_V2_ENABLED","PREF_WAKELOCK_BLOCKER_ENABLED"})
            Policy.oneOf(values.getOrDefault(key,"false"),"true","false");
        boolean cleanupEnabled=Boolean.parseBoolean(values.getOrDefault("PREF_CLEAN_UP_ON_TASK_REMOVED","false"));
        boolean standbyEnabled=Boolean.parseBoolean(values.getOrDefault("PREF_SMART_STANDBY_V2_ENABLED","false"));
        boolean wakeLocksEnabled=Boolean.parseBoolean(values.getOrDefault("PREF_WAKELOCK_BLOCKER_ENABLED","false"));
        JSONArray resident=strings(directory.resolve("resident_pkgs.xml"));
        for(int i=0;i<resident.length();i++) app(result,resident.getString(i))
                .put("protected",true).put("resident",true).put("background","normal").put("autostart","allow").put("cpuNice",0);
        JSONArray cleanup=strings(directory.resolve("clean_up_on_task_removed_pkgs.xml"));
        if(cleanupEnabled) for(int i=0;i<cleanup.length();i++) app(result,cleanup.getString(i)).put("cleanupOnTaskRemoved",true);
        JSONArray standby=strings(directory.resolve("smart_stand_by_pkgs.xml"));
        if(standbyEnabled) for(int i=0;i<standby.length();i++) app(result,standby.getString(i)).put("background","restricted");
        JSONArray blocked=strings(directory.resolve("blocked_wakelocks.xml"));
        if(wakeLocksEnabled) for(int i=0;i<blocked.length();i++) {
            String entry=blocked.getString(i);int split=entry.indexOf('-');
            if(split<0) throw new IllegalArgumentException("Unrecognized WakeLock rule");
            String name=entry.substring(0,split);Policy.packageName(name);
            JSONObject target=app(result,name);
            if(!target.has("wakeLocks")) target.put("wakeLocks",new JSONArray());
            target.getJSONArray("wakeLocks").put(entry.substring(split+1));
        }
        var ops=xml(directory.resolve("op_settings.xml")).getElementsByTagName("string");
        for(int i=0;i<ops.getLength();i++) {
            Element item=(Element)ops.item(i);
            if(!item.getTextContent().equals("0")) throw new IllegalArgumentException("Non-allow legacy AppOps requires explicit conversion: "+item.getAttribute("name"));
        }
        JSONArray source=new JSONArray(Text.read(directory.resolve("rules.json")));
        result.put("rules",dev.local.fuxicontrol.core.LegacyRules.convert(source));
        JSONObject proxy=new JSONObject();
        String prefix="plugin.push.message.delegate.server.";
        String name="com.tencent.mm";
        for(var field:java.util.Map.of("enabled","channel_enabled_","showContent","channel_show_content_enabled_",
                "startOnPush","channel_start_app_on_push_enabled_","skipIfRunning","channel_skip_if_running_enabled_",
                "sound","channel_n_sound_enabled_","vibrate","channel_n_vibrate_enabled_").entrySet()) {
            String value=values.getOrDefault(prefix+field.getValue()+name,"false");
            Policy.oneOf(value,"true","false");proxy.put(field.getKey(),Boolean.parseBoolean(value));
        }
        result.getJSONObject("pushProxy").put(name,proxy);
        Policy.validate(result);
        return result;
    }
}
