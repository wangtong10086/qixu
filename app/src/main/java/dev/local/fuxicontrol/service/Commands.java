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
    public Commands(Store store,Catalog catalog,Audit audit,RuntimeEngine engine) {
        this.store=store;this.catalog=catalog;this.audit=audit;this.engine=engine;this.discovery=new AppDiscovery(catalog);
    }
    public synchronized JSONObject execute(JSONObject request) throws Exception {
        String command=request.getString("command");
        JSONObject config=store.get();
        if(request.has("expectedRevision") && request.getLong("expectedRevision")!=config.getLong("revision"))
            throw new IllegalStateException("Configuration changed; reload before saving");
        JSONObject result;
        switch(command) {
            case "status" -> { return engine.status().put("revision",config.getLong("revision"))
                    .put("mode",config.getString("mode")).put("schema",1); }
            case "capabilities" -> { return engine.capabilities(); }
            case "apps" -> { return new JSONObject().put("apps",catalog.applications()); }
            case "apps.index" -> { return discovery.index(config,request.optString("query",""),request.optString("scope","all")); }
            case "app.icon" -> { return discovery.icon(request.getString("package")); }
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
            case "config.replace" -> { config=Policy.copy(request.getJSONObject("config")); }
            case "rules.get" -> { return new JSONObject().put("rules",config.getJSONArray("rules")); }
            case "rules.replace" -> { config.put("rules",request.getJSONArray("rules")); }
            case "audit" -> { return new JSONObject().put("records",audit.tail()); }
            case "migration.import" -> {
                JSONObject imported=Migration.convert(Path.of(request.getString("directory")));
                imported.put("mode","observe");config=imported;
            }
            case "event" -> { return engine.event(request); }
            case "runtime.reconcile" -> { engine.reconcile();return engine.status(); }
            case "network.stats" -> { return engine.networkStats(request.optInt("uid",-1)); }
            default -> throw new IllegalArgumentException("Unknown command: "+command);
        }
        boolean preview=request.optBoolean("dryRun",false);
        engine.preflight(config);
        result=store.replace(config,preview);
        if(!preview) { audit.record(command,new JSONObject().put("revision",result.getLong("revision")));engine.reconcile(); }
        return new JSONObject().put("dryRun",preview).put("config",result);
    }
}
