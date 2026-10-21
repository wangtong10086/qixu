package dev.local.fuxicontrol.service;

import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RuntimeEngine {
    private final Store store;private final Catalog catalog;private final Audit audit;
    private final ResourceController resources;
    private final PrivacyController privacy;
    private final ResidentController resident;
    private final StandbyController standby;
    private final AudioActivity audio;
    private final ServiceController services;
    private final android.os.PowerManager power;
    private final Map<String,java.util.concurrent.ScheduledFuture<?>> pendingScreenCleanup=new HashMap<>();
    private boolean screenKnown;
    private boolean screenInteractive=true;
    private long cleanupRevision=-1,cleanupGeneration;
    private final NetworkController network=new NetworkController();
    private final Map<String,JSONObject> runtime=new HashMap<>();
    private final Map<Integer,Integer> owners=new HashMap<>();
    private final Set<Integer> foreground=new HashSet<>();
    private final Set<Integer> activeTasks=new HashSet<>();
    private final Map<String,Boolean> hooks=new HashMap<>();
    private final java.util.concurrent.ScheduledThreadPoolExecutor tasks=new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    private long hookSeen;
    private long hookRevision=-1;
    private long killSequence;
    private final Map<String,Long> pendingKills=new HashMap<>();
    private boolean reconcileQueued;
    private String lastError="";
    public RuntimeEngine(Store store,Catalog catalog,Audit audit) throws Exception {
        this.store=store;this.catalog=catalog;this.audit=audit;resources=new ResourceController(store.directory);
        standby=new StandbyController(catalog,store.directory);privacy=new PrivacyController(catalog,store.directory);resident=new ResidentController();audio=new AudioActivity(catalog.context());
        services=new ServiceController(catalog,store.directory);power=catalog.context().getSystemService(android.os.PowerManager.class);
        tasks.setRemoveOnCancelPolicy(true);
    }
    public synchronized Set<Integer> activeTaskUids() {return Set.copyOf(activeTasks);}
    public synchronized JSONObject status() throws org.json.JSONException {
        return new JSONObject().put("service",true).put("hookLastSeen",hookSeen).put("hooks",new JSONObject(hooks))
                .put("hookRevision",hookRevision).put("lastError",lastError).put("foregroundUids",new JSONArray(foreground)).put("activeTaskUids",new JSONArray(activeTasks)).put("applications",runtime.size()).put("uptimeMs",android.os.SystemClock.elapsedRealtime())
                .put("screenCleanup",new JSONObject().put("screenKnown",screenKnown).put("interactive",power.isInteractive()).put("pending",new JSONArray(pendingScreenCleanup.keySet())));
    }
    public JSONObject capabilities() throws org.json.JSONException {
        return new JSONObject().put("androidSdk",android.os.Build.VERSION.SDK_INT).put("requiredVectorApi",102)
                .put("memoryHigh",Files.exists(Path.of("/sys/fs/cgroup/apps/memory.high")))
                .put("memoryMax",Files.exists(Path.of("/sys/fs/cgroup/apps/memory.max")))
                .put("cpuBandwidthQuota",false).put("uclamp",Files.exists(Path.of("/dev/cpuctl/background/cpu.uclamp.max")))
                .put("networkLoaded",Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/limits")))
                .put("hookExecution",new JSONObject(hooks));
    }
    public synchronized JSONObject packageStatus(String name) throws org.json.JSONException {
        return runtime.containsKey(name)?runtime.get(name):new JSONObject().put("state","not-reconciled");
    }
    private String missingHook(JSONObject config) throws org.json.JSONException {
        var required=new java.util.LinkedHashSet<>(java.util.List.of("startup","foreground","wakelock","push","jobs","task-removed","provider","push-broadcast","active-task","freezer","package-observer","broadcast","process-started","permissions-observer"));
        boolean screenCleanup=!config.getJSONObject("defaults").optString("screenOffCleanup","off").equals("off");
        for(String name:dev.local.fuxicontrol.core.Json.keys(config.getJSONObject("apps")))
            screenCleanup|=!config.getJSONObject("apps").getJSONObject(name).optString("screenOffCleanup","off").equals("off");
        if(screenCleanup) required.add("screen-state");
        JSONObject proxies=config.optJSONObject("pushProxy");
        if(proxies!=null) for(String name:dev.local.fuxicontrol.core.Json.keys(proxies))
            if(dev.local.fuxicontrol.core.Policy.pushProxy(config,name).getBoolean("enabled")) {
                required.add("push-proxy");required.add("native-notification");
            }
        for(String feature:required) if(!Boolean.TRUE.equals(hooks.get(feature))) return feature;
        return "";
    }
    public synchronized void requireReady(JSONObject config) throws org.json.JSONException {
        String missing=missingHook(config);
        if(!missing.isEmpty()) throw new IllegalStateException("Required hook not ready: "+missing);
    }
    public synchronized void reconcile() throws Exception {
      try {
        JSONObject config=store.get();
        JSONObject snapshot=catalog.snapshot(config);
        boolean enforce=config.getString("mode").equals("enforce");
        JSONObject effective=snapshot.getJSONObject("effective");
        scheduleScreenCleanup(config,effective);
        String missing=enforce?missingHook(config):"";
        if(!missing.isEmpty()) {
            // Boot and daemon restarts have no foreground snapshot until the hook reconnects.
            privacy.apply(effective,true);
            lastError="Waiting for system hook: "+missing;
            return;
        }
        if(!enforce) {resources.restoreAll();Native.networkClear();network.restore();}
        if(enforce) {
            boolean needed=false;
            for(String name:dev.local.fuxicontrol.core.Json.keys(effective)) {
                JSONObject p=effective.getJSONObject(name);
                needed |= p.getLong("uploadBps")>0 || p.getLong("downloadBps")>0 || !p.getString("network").equals("allow");
            }
            if(needed) {
                network.ensure();
                for(var entry:owners.entrySet()) Native.networkAlias(entry.getKey(),entry.getValue());
            } else {Native.networkClear();network.restore();}
            resources.scanProcesses();
        }
        Set<Integer> playing=enforce?audio.activeUids():Set.of();
        Set<Integer> seen=new HashSet<>();
        for(String name:dev.local.fuxicontrol.core.Json.keys(effective)) {
            JSONObject p=effective.getJSONObject(name);int uid=p.getInt("uid");
            JSONObject result=new JSONObject().put("mode",config.getString("mode")).put("uid",uid).put("configured",p);
            if(enforce && seen.add(uid)) {
                boolean active=foreground.contains(uid)||activeTasks.contains(uid)||playing.contains(uid)||p.optBoolean("protected",false);
                resources.memory(uid,active?0:p.getLong("memoryHighMiB"),active?0:p.getLong("memoryMaxMiB"));
                resources.scheduling(uid,p.getInt("cpuNice"),p.getInt("uclampMax"),!active);
                for(var owner:owners.entrySet()) if(owner.getValue()==uid && owner.getKey()!=uid) {
                    resources.memory(owner.getKey(),active?0:p.getLong("memoryHighMiB"),active?0:p.getLong("memoryMaxMiB"));
                    resources.scheduling(owner.getKey(),p.getInt("cpuNice"),p.getInt("uclampMax"),!active);
                }
                int deny=p.getString("network").equals("deny") || (p.getString("network").equals("background-deny")&&!active)?3:0;
                if(p.getLong("uploadBps")>0 || p.getLong("downloadBps")>0 || deny!=0) {
                    result.put("network",new JSONObject(Native.networkApply(uid,p.getLong("uploadBps"),p.getLong("downloadBps"),p.getInt("burstBytes"),deny)));
                } else if(Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/limits"))) Native.networkApply(uid,0,0,p.getInt("burstBytes"),0);

                result.put("applied",true);
            } else result.put("applied",false).put("reason",enforce?"shared-uid":"observation-only");
            runtime.put(name,result);
        }
        privacy.apply(effective,enforce);resident.apply(effective,enforce);services.apply(effective,enforce);
        Set<Integer> active=new HashSet<>(foreground);active.addAll(activeTasks);active.addAll(playing);
        standby.apply(effective,enforce,active);
        resources.flush();lastError="";
      } catch(Exception failure) {
        lastError=failure.getClass().getSimpleName()+": "+failure.getMessage();throw failure;
      }
    }
    public synchronized void preflight(JSONObject config) throws Exception {
        dev.local.fuxicontrol.core.Policy.validate(config);
        JSONObject effective=catalog.snapshot(config).getJSONObject("effective");
        privacy.validate(config.getJSONObject("apps"));
        services.validate(config,store.get());
        privacy.validate(new JSONObject().put("defaults",config.getJSONObject("defaults")));
        if(config.getString("mode").equals("enforce")) requireReady(config);
        java.util.Map<Integer,String> shared=new java.util.HashMap<>();
        for(String name:dev.local.fuxicontrol.core.Json.keys(effective)) {
            JSONObject p=effective.getJSONObject(name);
            JSONObject uidPolicy=new JSONObject();
            for(String field:new String[]{"cpuNice","uclampMax","memoryHighMiB","memoryMaxMiB","uploadBps","downloadBps","burstBytes","network","permissions","appOps"})
                uidPolicy.put(field,p.get(field));
            String encoded=uidPolicy.toString(),previous=shared.putIfAbsent(p.getInt("uid"),encoded);
            if(previous!=null && !previous.equals(encoded)) throw new IllegalArgumentException("Conflicting shared UID resource policy: "+name);
        }
    }
    public synchronized JSONObject event(JSONObject event) throws Exception {
        String type=event.getString("type");
        if(type.equals("hook-ready")) {
            JSONObject available=event.getJSONObject("hooks");
            for(String key:dev.local.fuxicontrol.core.Json.keys(available)) hooks.put(key,available.getBoolean(key));
            if(event.has("owners")) {
                owners.clear();JSONObject seeded=event.getJSONObject("owners");
                for(String uid:dev.local.fuxicontrol.core.Json.keys(seeded)) owners.put(Integer.parseInt(uid),seeded.getInt(uid));
            }
            if(event.has("foreground")) {
                foreground.clear();activeTasks.clear();
                var visible=event.getJSONArray("foreground");var active=event.getJSONArray("activeTasks");
                for(int i=0;i<visible.length();i++) foreground.add(visible.getInt(i));
                for(int i=0;i<active.length();i++) activeTasks.add(active.getInt(i));
                if(store.get().getString("mode").equals("enforce")) requestReconcile();
            }
            hookRevision=event.optLong("revision",-1);
            if(event.has("interactive")) screenChanged(event.getBoolean("interactive"));
            hookSeen=System.currentTimeMillis();return new JSONObject().put("recorded",true);
        }
        if(type.equals("feature-error")) {
            hooks.put(event.getString("feature"),false);
            audit.record(type,new JSONObject().put("feature",event.getString("feature")));return new JSONObject().put("recorded",true);
        }
        int uid=event.optInt("uid",-1);
        boolean changed=false;
        if(type.equals("screen-state")) {
            screenChanged(event.getBoolean("interactive"));
            audit.record(type,new JSONObject().put("interactive",event.getBoolean("interactive")));
            return new JSONObject().put("recorded",true);
        } else if(type.equals("process-started")) {
            owners.put(uid,event.getInt("owner"));
            if(Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/owners"))) Native.networkAlias(uid,event.getInt("owner"));
            changed=true;
        } else if(type.equals("process-gone")) {
            owners.remove(uid);foreground.remove(uid);activeTasks.remove(uid);
            if(Files.exists(Path.of("/sys/fs/bpf/fuxicontrol/owners"))) Native.networkAlias(uid,-1);
            changed=true;
        } else if(type.equals("permissions-changed")) {
            changed=true;
        } else if(type.equals("foreground")) {
            boolean visible=event.getBoolean("active");
            boolean wasForeground=foreground.contains(uid);
            if(visible) {
                foreground.add(uid);
                if(!event.optString("package").isEmpty()) pendingKills.remove(event.getString("package"));
            } else foreground.remove(uid);
            changed=wasForeground!=visible;
            if(wasForeground && !visible && !event.optString("package").isEmpty()) runRules("foreground-left",event.getString("package"));
        } else if(type.equals("active-task")) {
            changed=event.getBoolean("active")?activeTasks.add(uid):activeTasks.remove(uid);
        } else if(type.equals("push-arrived")) runRules(type,event.getString("package"));
        else if(type.equals("task-removed")) {
            JSONObject p=catalog.snapshot(store.get()).getJSONObject("effective").getJSONObject(event.getString("package"));
            if(p.getBoolean("cleanupOnTaskRemoved")) scheduleKill(event.getString("package"),30000);
        } else if(!Set.of("blocked","package-changed").contains(type)) throw new IllegalArgumentException("Unknown hook event");
        if(type.equals("package-changed")) {catalog.invalidate();store.replace(store.get(),false);}
        audit.record(type,new JSONObject().put("uid",uid).put("package",event.optString("package","")).put("reason",event.optString("reason","")).put("active",event.optBoolean("active",false)).put("proxyEligible",event.optBoolean("proxyEligible",false)).put("proxyState",event.optString("proxyState","")));
        if(type.equals("package-changed") || (changed && store.get().getString("mode").equals("enforce"))) requestReconcile();
        return new JSONObject().put("recorded",true);
    }
    private void requestReconcile() {
        if(reconcileQueued) return;
        reconcileQueued=true;
        tasks.execute(()-> {
            synchronized(RuntimeEngine.this) {
                try {reconcile();}
                catch(Exception failure) {android.util.Log.e("FuxiControl","Reconciliation failed",failure);}
                finally {reconcileQueued=false;}
            }
        });
    }
    private void screenChanged(boolean interactive) throws Exception {
        if(screenKnown && screenInteractive==interactive) return;
        screenKnown=true;screenInteractive=interactive;cleanupRevision=-1;
        JSONObject config=store.get();
        scheduleScreenCleanup(config,catalog.snapshot(config).getJSONObject("effective"));
    }
    private void scheduleScreenCleanup(JSONObject config,JSONObject effective) throws Exception {
        if(cleanupRevision==config.getLong("revision")) return;
        cleanupRevision=config.getLong("revision");long generation=++cleanupGeneration;
        pendingScreenCleanup.values().forEach(task->task.cancel(false));pendingScreenCleanup.clear();
        if(!screenKnown || screenInteractive || !config.getString("mode").equals("enforce")) return;
        Set<String> running=runningPackages();
        for(String name:dev.local.fuxicontrol.core.Json.keys(effective)) {
            JSONObject policy=effective.getJSONObject(name);
            if(policy.getString("screenOffCleanup").equals("off") || !running.contains(name)) continue;
            pendingScreenCleanup.put(name,tasks.schedule(()->{
                synchronized(RuntimeEngine.this) {
                    if(generation!=cleanupGeneration) return;
                    pendingScreenCleanup.remove(name);
                    try {cleanupScreenApp(name);}
                    catch(Exception failure) {
                        lastError="Screen cleanup: "+failure.getMessage();
                        android.util.Log.e("FuxiControl","Screen cleanup failed",failure);
                    }
                }
            },policy.getLong("screenOffDelaySeconds"),TimeUnit.SECONDS));
        }
    }
    private Set<String> runningPackages() {
        var processes=catalog.context().getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();
        Set<String> result=new HashSet<>();
        if(processes!=null) for(var process:processes) if(process.pkgList!=null) java.util.Collections.addAll(result,process.pkgList);
        return result;
    }
    private Set<Integer> protectedCleanupUids(JSONObject effective) throws Exception {
        Set<Integer> result=new HashSet<>();
        JSONArray apps=catalog.applications();
        for(int i=0;i<apps.length();i++) {
            JSONObject app=apps.getJSONObject(i),p=effective.getJSONObject(app.getString("package"));
            if(app.getBoolean("protected") || p.optBoolean("protected",false) || p.getBoolean("resident")) result.add(p.getInt("uid"));
        }
        return result;
    }
    private void cleanupScreenApp(String name) throws Exception {
        JSONObject config=store.get(),effective=catalog.snapshot(config).getJSONObject("effective");
        JSONObject policy=effective.optJSONObject(name);
        if(policy==null || !runningPackages().contains(name)) return; // Exited or uninstalled while waiting.
        String action=policy.getString("screenOffCleanup");if(action.equals("off")) return;
        int uid=policy.getInt("uid");
        String skip=dev.local.fuxicontrol.core.CleanupDecision.skipReason(power.isInteractive(),config.getString("mode").equals("enforce"),
                protectedCleanupUids(effective).contains(uid),policy.getBoolean("resident"),foreground.contains(uid),
                activeTasks.contains(uid)||services.foregroundUids().contains(uid),audio.activeUids().contains(uid));
        JSONObject record=new JSONObject().put("package",name).put("action",action);
        if(!skip.isEmpty()) {audit.record("screen-cleanup-skipped",record.put("reason",skip));return;}
        // Recheck the physical screen immediately before invoking the system operation.
        if(power.isInteractive()) {audit.record("screen-cleanup-skipped",record.put("reason","screen-on"));return;}
        Shell.run("am",action.equals("cached")?"kill":"force-stop","--user","0",name);
        audit.record("screen-cleanup-requested",record);
    }
    public synchronized JSONObject listServices(String name) throws Exception {return services.list(name,store.get());}
    public synchronized JSONObject stopService(String name,String cls,boolean dryRun) throws Exception {
        JSONObject effective=catalog.snapshot(store.get()).getJSONObject("effective");
        int uid=effective.getJSONObject(name).getInt("uid");
        if(protectedCleanupUids(effective).contains(uid) || foreground.contains(uid) || activeTasks.contains(uid)
                || services.foregroundUids().contains(uid) || audio.activeUids().contains(uid))
            throw new IllegalArgumentException("当前应用受保护或正在执行前台任务，不能停止服务");
        if(dryRun) {
            catalog.context().getPackageManager().getServiceInfo(new android.content.ComponentName(name,cls),PackageManager.MATCH_DISABLED_COMPONENTS);
            return new JSONObject().put("dryRun",true).put("package",name).put("class",cls);
        }
        var result=services.stop(name,cls);audit.record("service-stop",result);return result;
    }
    private void runRules(String event,String name) throws Exception {
        JSONObject config=store.get();if(!config.getString("mode").equals("enforce")) return;
        JSONArray rules=config.getJSONArray("rules");
        for(int i=0;i<rules.length();i++) {
            JSONObject rule=rules.getJSONObject(i);
            if(!dev.local.fuxicontrol.core.Rules.matches(rule,event,name)) continue;
            if(rule.getString("action").equals("record")) audit.record("rule-record",new JSONObject().put("id",rule.getString("id")).put("package",name));
            else scheduleKill(name,rule.getLong("delayMs"));
        }
    }
    private void scheduleKill(String name,long delay) {
        long sequence=++killSequence;pendingKills.put(name,sequence);
        tasks.schedule(()-> {
            synchronized(RuntimeEngine.this) {
            try {
                if(!Long.valueOf(sequence).equals(pendingKills.get(name))) return;
                pendingKills.remove(name);
                JSONObject p=catalog.snapshot(store.get()).getJSONObject("effective").getJSONObject(name);
                int uid=p.getInt("uid");
                if(!store.get().getString("mode").equals("enforce") || p.optBoolean("protected",false) || p.getBoolean("resident") || foreground.contains(uid) || activeTasks.contains(uid) || audio.activeUids().contains(uid)) {
                    audit.record("reclaim-skipped-active",new JSONObject().put("package",name));return;
                }
                Shell.run("am","kill",name);
                audit.record("reclaim",new JSONObject().put("package",name));
            } catch(Exception e) {lastError=e.getClass().getSimpleName()+": "+e.getMessage();android.util.Log.e("FuxiControl","Rule failed",e);}
            }
        },delay,TimeUnit.MILLISECONDS);
    }
    public JSONObject networkStats(int uid) throws org.json.JSONException {return new JSONObject(Native.networkStats(uid));}
}
