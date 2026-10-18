package dev.local.fuxicontrol.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Translate only verified user rules; never execute imported MVEL or shell expressions. */
public final class LegacyRules {
    private static final Map<String,String> VERIFIED=Map.of(
        "Codex WeChat FCM Transport Telemetry","8d87f95f01c4765fd556b48a42341cbf48cb6d65bd60d770a58f49e66908ed3e",
        "Codex WeChat FCM Nonstop Reclaimer","b4a9fd43217762de44038ce34e22481f9a40173e7bd3a608ee5184018974246d",
        "Codex WeChat Post-Use Nonstop Reclaimer","cc1d094a01f47687bcd79bdb863c4caa848739f69d29ebe8a105b6ccd3c25bc8");
    public static JSONArray convert(JSONArray records) throws java.security.NoSuchAlgorithmException {
        JSONArray result=new JSONArray();
        for(int i=0;i<records.length();i++) {
            JSONObject record=records.getJSONObject(i);
            JSONArray definitions=new JSONArray(record.getString("rawJson"));
            if(definitions.length()!=1) throw new IllegalArgumentException("Unsupported legacy rule group");
            JSONObject old=definitions.getJSONObject(0);String name=old.getString("name");
            StringBuilder semantic=new StringBuilder(old.getString("condition")).append('\0');
            JSONArray actions=old.getJSONArray("actions");
            for(int j=0;j<actions.length();j++) {if(j!=0) semantic.append('\0');semantic.append(actions.getString(j));}
            semantic.append('\0').append(old.getLong("delay")).append('\0').append(old.getInt("priority"));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(semantic.toString().getBytes(StandardCharsets.UTF_8)));
            if(!hash.equals(VERIFIED.get(name))) throw new IllegalArgumentException("Legacy rule requires review: "+name);
            boolean telemetry=name.equals("Codex WeChat FCM Transport Telemetry");
            result.put(new JSONObject().put("id","migrated-"+i).put("name",name)
                .put("enabled",record.getInt("enabled")==1).put("package","com.tencent.mm")
                .put("event",name.equals("Codex WeChat Post-Use Nonstop Reclaimer")?"foreground-left":"push-arrived")
                .put("action",telemetry?"record":"kill-cached").put("delayMs",old.getLong("delay")));
        }
        return result;
    }
}
