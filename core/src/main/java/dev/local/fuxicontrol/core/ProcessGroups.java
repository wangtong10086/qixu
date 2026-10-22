package dev.local.fuxicontrol.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Aggregates actual process package membership, never inferring liveness from a shared UID. */
public final class ProcessGroups {
    public static JSONObject group(JSONArray processes,JSONObject installedUids,Set<Integer> activeTaskUids) {
        JSONObject apps=new JSONObject();
        for(int i=0;i<processes.length();i++) {
            JSONObject process=processes.getJSONObject(i);
            JSONArray packages=process.getJSONArray("packages");
            Set<String> seen=new HashSet<>();
            for(int j=0;j<packages.length();j++) {
                String name=packages.getString(j);
                if(!installedUids.has(name) || !seen.add(name)) continue;
                if(!apps.has(name)) apps.put(name,new JSONObject().put("uid",installedUids.getInt(name)).put("importance",Integer.MAX_VALUE)
                        .put("processes",new JSONArray()).put("activeTask",activeTaskUids.contains(installedUids.getInt(name)))
                        .put("foregroundService",false));
                JSONObject app=apps.getJSONObject(name);
                app.getJSONArray("processes").put(process);
                if(process.getInt("importance")<app.getInt("importance"))
                    app.put("importance",process.getInt("importance")).put("state",process.getString("state"));
                app.put("foregroundService",app.getBoolean("foregroundService") || process.getBoolean("foregroundService"));
            }
        }
        return apps;
    }
}
