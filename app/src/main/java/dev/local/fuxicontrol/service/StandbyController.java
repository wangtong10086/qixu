package dev.local.fuxicontrol.service;

import dev.local.fuxicontrol.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Use Android's UID-idle/service shutdown and app-standby machinery for restricted apps. */
public final class StandbyController {
    private final Path file;
    private final Catalog catalog;
    private final JSONObject journal;
    public StandbyController(Catalog catalog,Path directory) throws Exception {
        this.catalog=catalog;
        file=directory.resolve("standby.json");
        journal=Files.exists(file)?new JSONObject(Text.read(file)):new JSONObject();
    }
    private static int bucket(String name) throws Exception {
        return Integer.parseInt(Shell.run("am","get-standby-bucket","--user","0",name).trim());
    }
    public void apply(JSONObject effective,boolean enforce,Set<Integer> active) throws Exception {
        Set<String> desired=new HashSet<>();
        if(enforce) for(String name:Json.keys(effective)) {
            JSONObject policy=effective.getJSONObject(name);
            if(!policy.getString("background").equals("restricted") || policy.optBoolean("protected") || active.contains(policy.getInt("uid"))) continue;
            desired.add(name);
            if(journal.has(name)) continue;
            int before=bucket(name);
            journal.put(name,new JSONObject().put("before",before).put("uid",policy.getInt("uid")));persist();
            Shell.run("am","make-uid-idle","--user","0",name);
            Shell.run("am","set-inactive","--user","0",name,"true");
            journal.getJSONObject(name).put("applied",bucket(name));persist();
        }
        for(String name:new HashSet<>(Json.keys(journal))) if(!desired.contains(name)) {
            var entry=journal.getJSONObject(name);
            try {
                if(catalog.context().getPackageManager().getApplicationInfo(name,0).uid!=entry.getInt("uid")) {
                    journal.remove(name);persist();continue;
                }
            } catch(android.content.pm.PackageManager.NameNotFoundException removed) {journal.remove(name);persist();continue;}
            if(!entry.has("applied") || bucket(name)==entry.getInt("applied"))
                Shell.run("am","set-standby-bucket","--user","0",name,Integer.toString(entry.getInt("before")));
            journal.remove(name);persist();
        }
    }
    private void persist() throws Exception {Store.atomic(file,journal.toString(2));}
}
