package dev.local.fuxicontrol.service;

import dev.local.fuxicontrol.core.Policy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Commands {
    private final Store store;
    private final Catalog catalog;
    private final Audit audit;
    private final RuntimeEngine engine;
    private final AppDiscovery discovery;
    private final SplashChannel splash;
    private final ResourceMonitor resources;
    private final ManualCleanupController cleanup;
    public Commands(Store store,Catalog catalog,Audit audit,RuntimeEngine engine,SplashChannel splash,ResourceMonitor resources) {
        this.store=store;this.catalog=catalog;this.audit=audit;this.engine=engine;this.discovery=new AppDiscovery(catalog);this.splash=splash;this.resources=resources;this.cleanup=new ManualCleanupController(store,engine,resources);
    }
    public synchronized JSONObject execute(JSONObject request) throws Exception {
        String command=request.getString("command");
        JSONObject config=store.get();
        if(request.has("expectedRevision") && request.getLong("expectedRevision")!=config.getLong("revision"))
            throw new IllegalStateException("Configuration changed; reload before saving");
        JSONObject result;
        switch(command) {
            case "cleanup.preview" -> {return cleanup.preview();}
            case "cleanup.run" -> {return cleanup.run(request.getString("id"),request.getLong("expectedRevision"));}
            case "cleanup.status" -> {return cleanup.status();}
            case "status" -> { return engine.status().put("revision",config.getLong("revision"))
                    .put("mode",config.getString("mode")).put("schema",config.getInt("schema")); }
            case "capabilities" -> { return engine.capabilities(); }
            case "apps" -> { return new JSONObject().put("apps",catalog.applications()); }
            case "apps.index" -> { return discovery.index(config,request.optString("query",""),request.optString("scope","all")); }
            case "app.icon" -> { return discovery.icon(request.getString("package")); }
            case "ads.status" -> { return splash.status(); }
            case "ads.retry" -> {
                String name=request.getString("package");Policy.packageName(name);
                boolean dryRun=request.optBoolean("dryRun",false);
                result=splash.retry(name,dryRun);
                if(!dryRun) audit.record(command,new JSONObject().put("package",name));
                return result;
            }
            case "services.list" -> { return engine.listServices(request.getString("package")); }
            case "service.stop" -> { return engine.stopService(request.getString("package"),request.getString("class"),request.optBoolean("dryRun",false)); }
            case "service.policy" -> {
                String name=request.getString("package"),cls=request.getString("class");Policy.packageName(name);
                String mode=request.getString("mode");Policy.oneOf(mode,"block","system");
                var apps=config.getJSONObject("apps");
                if(!apps.has(name)) apps.put(name,new JSONObject());
                var app=apps.getJSONObject(name);
                if(!app.has("services")) app.put("services",new JSONObject());
                if(mode.equals("block")) app.getJSONObject("services").put(cls,"block");
                else app.getJSONObject("services").remove(cls);
            }
            case "resources.snapshot" -> {
                JSONObject value=resources.snapshot();
                if(request.has("package")) {
                    String name=request.getString("package");Policy.packageName(name);
                    JSONObject apps=value.optJSONObject("apps"),filtered=new JSONObject();
                    if(apps!=null&&apps.has(name))filtered.put(name,apps.get(name));
                    value.put("apps",filtered).put("package",name);
                }
                return value;
            }
            case "resources.detail" -> {return resources.detail(request.getString("package"),request.optString("process",""));}
            case "resources.history" -> {return resources.history(request.optString("scope","system"),request.optString("identity",""),request.optLong("since",System.currentTimeMillis()-86400000));}
            case "resources.settings" -> {return resources.settings(request.has("history")?request.getBoolean("history"):null);}
            case "resources.clear" -> {return resources.clear();}
            case "process.policy.patch", "process.policy.inherit" -> {
                String name=request.getString("package"),process=request.getString("process");Policy.packageName(name);
                JSONObject apps=config.getJSONObject("apps"),app=apps.optJSONObject(name);
                if(app==null) {app=new JSONObject();apps.put(name,app);}
                JSONObject rules=app.optJSONObject("processes");if(rules==null) {rules=new JSONObject();app.put("processes",rules);}
                if(command.equals("process.policy.inherit")) rules.remove(process);
                else rules.put(process,request.getJSONObject("rule"));
            }
            case "apps.batch.patch" -> {
                JSONObject patch=request.getJSONObject("patch");Policy.validatePatch(patch);
                for(String field:dev.local.fuxicontrol.core.Json.keys(patch)) if(!dev.local.fuxicontrol.core.ProcessPolicy.FIELDS.contains(field))
                    throw new IllegalArgumentException("Batch resource fields only");
                JSONArray names=request.getJSONArray("packages");
                for(int i=0;i<names.length();i++) {
                    String name=names.getString(i);Policy.packageName(name);
                    JSONObject apps=config.getJSONObject("apps"),app=apps.optJSONObject(name);
                    if(app==null) {app=new JSONObject();apps.put(name,app);}
                    for(String field:dev.local.fuxicontrol.core.Json.keys(patch)) app.put(field,patch.get(field));
                }
            }
            case "apps.running" -> { return discovery.running(engine.activeTaskUids()); }
            case "config.get" -> { return config; }
            case "defaults.get" -> { return config.getJSONObject("defaults"); }
            case "app.get", "explain" -> {
                String name=request.getString("package");Policy.packageName(name);
                JSONObject snapshot=catalog.snapshot(config);
                JSONObject effective=snapshot.getJSONObject("effective").getJSONObject(name);
                return new JSONObject().put("revision",config.getLong("revision")).put("package",name).put("effective",effective).put("overrides",config.getJSONObject("apps").optJSONObject(name)==null?new JSONObject():config.getJSONObject("apps").getJSONObject(name)).put("runtime",engine.packageStatus(name));
            }
            case "defaults.patch", "app.patch", "app.replace" -> {
                JSONObject patch=request.getJSONObject("patch");Policy.validatePatch(patch);
                JSONObject target=config.getJSONObject("defaults");
                if(!command.equals("defaults.patch")) {
                    String name=request.getString("package");Policy.packageName(name);
                    target=config.getJSONObject("apps").optJSONObject(name);
                    if(target==null || command.equals("app.replace")) {target=new JSONObject();config.getJSONObject("apps").put(name,target);}
                }
                for(String key:dev.local.fuxicontrol.core.Json.keys(patch)) target.put(key,patch.get(key));
            }
            case "app.inherit" -> {
                String name=request.getString("package");Policy.packageName(name);config.getJSONObject("apps").remove(name);
            }
            case "mode.set" -> {
                String mode=request.getString("mode");Policy.oneOf(mode,"observe","enforce","disabled");
                if(mode.equals("enforce")) engine.requireReady(config);
                config.put("mode",mode);
            }
            case "profile.create" -> { dev.local.fuxicontrol.core.Profiles.create(config,request.getString("name")); }
            case "profile.activate" -> { config=dev.local.fuxicontrol.core.Profiles.activate(config,request.getString("id")); }
            case "config.replace" -> { config=Policy.migrate(request.getJSONObject("config")); }
            case "rules.get" -> { return new JSONObject().put("rules",config.getJSONArray("rules")); }
            case "rules.replace" -> { config.put("rules",request.getJSONArray("rules")); }
            case "audit" -> { return new JSONObject().put("records",audit.tail()); }
            case "migration.import" -> {
                JSONObject imported=Migration.convert(Path.of(request.getString("directory")));
                imported.put("mode","observe");config=imported;
            }
            case "event" -> { return engine.event(request); }
            case "runtime.reconcile" -> { engine.reconcile();splash.reconcileScope(config);return engine.status(); }
            case "network.stats" -> { return engine.networkStats(request.optInt("uid",-1)); }
            default -> throw new IllegalArgumentException("Unknown command: "+command);
        }
        boolean preview=request.optBoolean("dryRun",false);
        engine.preflight(config);
        result=store.replace(config,preview);
        if(!preview) { audit.record(command,new JSONObject().put("revision",result.getLong("revision")));engine.reconcile();splash.reconcileScope(result); }
        return new JSONObject().put("dryRun",preview).put("config",result);
    }
}
