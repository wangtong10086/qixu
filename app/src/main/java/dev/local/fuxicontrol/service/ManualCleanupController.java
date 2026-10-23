package dev.local.fuxicontrol.service;

import org.json.*;
import java.util.concurrent.Executors;

/** A preview is a bounded target set. Running it never adds newly matching apps. */
final class ManualCleanupController {
    private final Store store;
    private final RuntimeEngine engine;
    private final ResourceMonitor resources;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONObject preview;
    private String state="idle",jobId="",error="";
    private JSONArray results=new JSONArray();
    ManualCleanupController(Store store,RuntimeEngine engine,ResourceMonitor resources) {this.store=store;this.engine=engine;this.resources=resources;}
    synchronized JSONObject preview() throws Exception {
        resources.prepareCleanup();
        JSONObject config=store.get();
        preview=engine.manualCleanupPreview(resources.snapshot());
        preview.put("id",java.util.UUID.randomUUID().toString()).put("revision",config.getLong("revision"))
                .put("createdElapsedMs",android.os.SystemClock.elapsedRealtime()).put("mode",config.getString("mode"));
        return new JSONObject(preview.toString());
    }
    synchronized JSONObject status() throws JSONException {return new JSONObject().put("state",state).put("id",jobId).put("error",error).put("results",new JSONArray(results.toString()));}
    synchronized JSONObject quick(boolean dryRun) throws Exception {
        if(state.equals("running")) return status();
        resources.prepareCleanup();
        JSONObject config=store.get(),plan=engine.quickCleanupPreview(resources.snapshot());
        if(dryRun || !config.getString("mode").equals("enforce")) return plan.put("state","preview").put("mode",config.getString("mode"));
        return submit(plan.getJSONArray("apps"),config.getLong("revision"),false,false);
    }
    synchronized JSONObject single(JSONObject request) throws Exception {
        dev.local.fuxicontrol.core.Policy.packageName(request.getString("package"));
        if(request.has("pid") && (request.getInt("pid")<=1 || request.getLong("startTicks")<=0)) throw new IllegalArgumentException("Invalid process identity");
        if(state.equals("running")) return status();
        if(request.optBoolean("dryRun")) throw new IllegalArgumentException("单项清理不支持 dry-run；请查询运行状态");
        return submit(new JSONArray().put(new JSONObject(request.toString())),store.get().getLong("revision"),true,request.optBoolean("overrideProtection"));
    }
    private JSONObject submit(JSONArray targets,long revision,boolean single,boolean override) throws JSONException {
        state="running";jobId=java.util.UUID.randomUUID().toString();error="";results=new JSONArray();
        worker.execute(()-> {
            try {
                for(int i=0;i<targets.length();i++) {
                    JSONObject target=targets.getJSONObject(i),result;
                    try {
                        if(!single && !target.getBoolean("eligible")) result=new JSONObject(target.toString()).put("state","skipped");
                        else if(store.get().getLong("revision")!=revision) result=new JSONObject(target.toString()).put("state","skipped").put("reason","配置已变化");
                        else result=single?engine.explicitCleanup(target,override):engine.quickCleanupOne(target.getString("package"),revision,resources.snapshot());
                    } catch(Exception failure) {result=new JSONObject().put("package",target.getString("package")).put("state","failed").put("reason",failure.toString());}
                    engine.recordCleanupResult(result,single?"single":"quick",override);
                    synchronized(this) {results.put(result);}
                }
                synchronized(this) {state="completed";}
            } catch(Exception failure) {
                synchronized(this) {state="failed";error=failure.toString();}
                android.util.Log.e("QixuCleanup","Cleanup job failed",failure);
            }
        });
        return status();
    }
    synchronized JSONObject run(String id,long revision) throws JSONException {
        if(state.equals("running")) throw new IllegalStateException("清理仍在进行");
        if(preview==null || !preview.getString("id").equals(id) || preview.getLong("revision")!=revision || store.get().getLong("revision")!=revision)
            throw new IllegalStateException("配置或预览已变化，请重新预览");
        if(android.os.SystemClock.elapsedRealtime()-preview.getLong("createdElapsedMs")>60000) throw new IllegalStateException("预览已过期，请重新预览");
        if(!store.get().getString("mode").equals("enforce")) throw new IllegalStateException("观察或停用模式只能预览");
        JSONArray targets=preview.getJSONArray("apps");preview=null;
        state="running";jobId=id;error="";results=new JSONArray();
        worker.execute(()-> {
            try {
                for(int i=0;i<targets.length();i++) {
                    JSONObject target=targets.getJSONObject(i);
                    if(!target.getBoolean("eligible")) continue;
                    JSONObject result;
                    try {result=engine.manualCleanupOne(target.getString("package"),revision,resources.snapshot());}
                    catch(Exception e) {result=new JSONObject().put("package",target.getString("package")).put("state","failed").put("reason",e.getMessage());}
                    synchronized(this) {results.put(result);}
                }
                synchronized(this) {state="completed";}
            } catch(Exception e) {synchronized(this) {state="failed";error=e.toString();}android.util.Log.e("QixuCleanup","Cleanup job failed",e);}
        });
        return status();
    }
}
