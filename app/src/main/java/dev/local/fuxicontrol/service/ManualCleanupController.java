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
