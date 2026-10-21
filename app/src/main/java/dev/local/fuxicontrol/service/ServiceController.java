package dev.local.fuxicontrol.service;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import dev.local.fuxicontrol.core.Json;
import dev.local.fuxicontrol.core.Policy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Component changes are reversible and never kill a currently running app. */
@SuppressWarnings("deprecation")
public final class ServiceController {
    private final Catalog catalog;
    private final PackageManager pm;
    private final ActivityManager activity;
    private final Path file;
    private final JSONObject journal;
    public ServiceController(Catalog catalog,Path directory) throws Exception {
        this.catalog=catalog;pm=catalog.context().getPackageManager();
        activity=catalog.context().getSystemService(ActivityManager.class);
        file=directory.resolve("services.json");
        journal=Files.exists(file)?new JSONObject(Text.read(file)):new JSONObject();
    }
    public Set<Integer> foregroundUids() {
        Set<Integer> result=new HashSet<>();
        for(var service:activity.getRunningServices(Integer.MAX_VALUE)) if(service.foreground) result.add(service.uid);
        return result;
    }
    public void validate(JSONObject config,JSONObject previous) throws Exception {
        var apps=catalog.applications();
        Set<Integer> protectedUids=new HashSet<>();
        for(int i=0;i<apps.length();i++) {
            var app=apps.getJSONObject(i);
            var override=config.getJSONObject("apps").optJSONObject(app.getString("package"));
            if(app.getBoolean("protected") || (override!=null && override.optBoolean("protected",false))) protectedUids.add(app.getInt("uid"));
        }
        Set<String> protectedNames=new HashSet<>();
        for(int i=0;i<apps.length();i++) if(protectedUids.contains(apps.getJSONObject(i).getInt("uid"))) protectedNames.add(apps.getJSONObject(i).getString("package"));
        for(String name:Json.keys(config.getJSONObject("apps"))) {
            JSONObject policy=config.getJSONObject("apps").getJSONObject(name);
            JSONObject rules=policy.optJSONObject("services");
            if(rules==null || rules.length()==0) continue;
            var old=previous.getJSONObject("apps").optJSONObject(name);
            var oldRules=old==null?null:old.optJSONObject("services");
            for(String cls:Json.keys(rules)) {
                boolean existing=oldRules!=null && oldRules.has(cls);
                // A role change suspends old rules; it must not prevent unrelated edits or restoration.
                if((protectedNames.contains(name) || policy.optBoolean("protected",false)) && !existing)
                    throw new IllegalArgumentException("关键服务不能停用组件："+name);
                try {pm.getServiceInfo(new ComponentName(name,cls),PackageManager.MATCH_DISABLED_COMPONENTS);}
                catch(PackageManager.NameNotFoundException removed) {
                    if(!existing) throw removed;
                    // Preserve an existing rule after an app update removed its component.
                    // The inventory exposes it as missing, with an explicit restore action.
                }
            }
        }
    }
    public synchronized void apply(JSONObject effective,boolean enforce) throws Exception {
        Set<String> desired=new HashSet<>();
        Set<Integer> protectedUids=new HashSet<>();
        var apps=catalog.applications();
        for(int i=0;i<apps.length();i++) if(apps.getJSONObject(i).getBoolean("protected")) protectedUids.add(apps.getJSONObject(i).getInt("uid"));
        for(String name:Json.keys(effective)) {
            var policy=effective.getJSONObject(name);
            if(policy.optBoolean("protected",false)) protectedUids.add(policy.getInt("uid"));
        }
        if(enforce) for(String name:Json.keys(effective)) {
            var policy=effective.getJSONObject(name);
            if(policy.optBoolean("protected",false) || protectedUids.contains(policy.getInt("uid"))) continue;
            for(String cls:Json.keys(policy.getJSONObject("services"))) {
                var component=new ComponentName(name,cls);
                try {pm.getServiceInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);}
                catch(PackageManager.NameNotFoundException removed) {continue;}
                String key=component.flattenToString();desired.add(key);
                int before=pm.getComponentEnabledSetting(component);
                if(before==PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue;
                if(!journal.has(key)) {journal.put(key,before);Store.atomic(file,journal.toString());}
                pm.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
            }
        }
        for(String key:new HashSet<>(Json.keys(journal))) if(!desired.contains(key)) {
            var component=ComponentName.unflattenFromString(key);
            try {
                pm.getServiceInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
                if(pm.getComponentEnabledSetting(component)==PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    pm.setComponentEnabledSetting(component,journal.getInt(key),PackageManager.DONT_KILL_APP);
            } catch(PackageManager.NameNotFoundException removed) { /* Package update or uninstall removed this component. */ }
            journal.remove(key);Store.atomic(file,journal.toString());
        }
    }
    public JSONObject list(String name,JSONObject config) throws Exception {
        Policy.packageName(name);
        var info=pm.getPackageInfo(name,PackageManager.GET_SERVICES|PackageManager.MATCH_DISABLED_COMPONENTS);
        var effective=catalog.snapshot(config).getJSONObject("effective").getJSONObject(name);
        var running=activity.getRunningServices(Integer.MAX_VALUE);
        var result=new JSONArray();Set<String> seen=new HashSet<>();
        var configured=effective.getJSONObject("services");
        if(info.services!=null) for(var service:info.services) {
            seen.add(service.name);
            var component=new ComponentName(name,service.name);
            var row=new JSONObject().put("class",service.name).put("declared",true).put("process",service.processName)
                    .put("enabled",service.enabled).put("setting",pm.getComponentEnabledSetting(component))
                    .put("blocked",configured.has(service.name)).put("exported",service.exported)
                    .put("running",false).put("foreground",false).put("clients",0).put("pid",0);
            for(var active:running) if(active.service.equals(component)) {
                row.put("running",active.pid>0).put("foreground",active.foreground).put("clients",active.clientCount)
                    .put("pid",active.pid).put("started",active.started).put("activeSince",active.activeSince);
                break;
            }
            result.put(row);
        }
        for(String cls:Json.keys(configured)) if(!seen.contains(cls))
            result.put(new JSONObject().put("class",cls).put("declared",false).put("blocked",true).put("running",false).put("foreground",false));
        boolean protectedUid=effective.optBoolean("protected",false);
        var apps=catalog.applications();
        for(int i=0;i<apps.length();i++) {
            var app=apps.getJSONObject(i);
            if(app.getInt("uid")==effective.getInt("uid")) {
                var override=config.getJSONObject("apps").optJSONObject(app.getString("package"));
                protectedUid|=app.getBoolean("protected") || (override!=null && override.optBoolean("protected",false));
            }
        }
        return new JSONObject().put("package",name).put("revision",config.getLong("revision"))
                .put("protected",protectedUid).put("services",result);
    }
    public JSONObject stop(String name,String cls) throws Exception {
        pm.getServiceInfo(new ComponentName(name,cls),PackageManager.MATCH_DISABLED_COMPONENTS);
        // app_process is not a registered application. Use the root Binder caller, without a fake app thread.
        Object manager=ActivityManager.class.getMethod("getService").invoke(null);
        int result=(Integer)Class.forName("android.app.IActivityManager").getMethod("stopService",
                Class.forName("android.app.IApplicationThread"),Intent.class,String.class,int.class)
                .invoke(manager,null,new Intent().setComponent(new ComponentName(name,cls)),null,0);
        if(result<0) throw new SecurityException("System denied stopping service: "+name+"/"+cls);
        return new JSONObject().put("stopped",result>0).put("package",name).put("class",cls);
    }
}
