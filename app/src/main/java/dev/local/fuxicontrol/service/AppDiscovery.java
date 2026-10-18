package dev.local.fuxicontrol.service;

import android.app.ActivityManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import dev.local.fuxicontrol.core.AppSearch;
import dev.local.fuxicontrol.core.ProcessGroups;
import dev.local.fuxicontrol.core.Policy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Read-only system metadata. Permission details never enter the hook's lightweight snapshots. */
public final class AppDiscovery {
    private final Catalog catalog;
    private final PackageManager pm;
    private final ActivityManager activity;
    public AppDiscovery(Catalog catalog) {
        this.catalog=catalog;pm=catalog.context().getPackageManager();
        activity=catalog.context().getSystemService(ActivityManager.class);
    }
    public JSONObject index(JSONObject config,String query,String scope) throws Exception {
        Policy.oneOf(scope,"all","name","declared","granted","policy");
        JSONArray result=new JSONArray();
        Map<String,PackageInfo> packages=new HashMap<>();
        for(PackageInfo info:pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) packages.put(info.packageName,info);
        Map<String,String> labels=new HashMap<>();
        JSONObject snapshot=catalog.snapshot(config);
        JSONArray apps=snapshot.getJSONArray("apps");
        for(int i=0;i<apps.length();i++) {
            JSONObject base=apps.getJSONObject(i);
            PackageInfo info=packages.get(base.getString("package"));
            // A package can disappear between the package-manager and catalog snapshots.
            if(info==null) continue;
            JSONArray permissions=new JSONArray();
            if(info.requestedPermissions!=null) for(int j=0;j<info.requestedPermissions.length;j++) {
                String name=info.requestedPermissions[j];
                if(!labels.containsKey(name)) {
                    String label;
                    try {label=pm.getPermissionInfo(name,0).loadLabel(pm).toString();}
                    catch(PackageManager.NameNotFoundException missingDefinition) {label=name;}
                    labels.put(name,label);
                }
                permissions.put(new JSONObject().put("name",name).put("label",labels.get(name))
                        .put("granted",(info.requestedPermissionsFlags[j]&PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0));
            }
            JSONObject app=new JSONObject(base.toString()).put("permissions",permissions).put("lastUpdateTime",info.lastUpdateTime);
            JSONObject effective=snapshot.getJSONObject("effective").getJSONObject(info.packageName);
            app.put("effective",effective);
            if(AppSearch.matches(app,effective,query,scope)) result.put(app);
        }
        return new JSONObject().put("apps",result).put("revision",config.getLong("revision"))
                .put("capturedAtElapsedMs",SystemClock.elapsedRealtime());
    }
    public JSONObject icon(String name) throws Exception {
        Policy.packageName(name);
        var drawable=pm.getApplicationIcon(name);
        var bitmap=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0,0,128,128);
            drawable.draw(new android.graphics.Canvas(bitmap));
            var output=new java.io.ByteArrayOutputStream();
            if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
                throw new java.io.IOException("Unable to encode application icon");
            return new JSONObject().put("png",android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP));
        } finally {bitmap.recycle();}
    }
    public JSONObject running(Set<Integer> activeTaskUids) throws Exception {
        JSONObject installed=new JSONObject();
        JSONArray apps=catalog.applications();
        for(int i=0;i<apps.length();i++) {JSONObject app=apps.getJSONObject(i);installed.put(app.getString("package"),app.getInt("uid"));}
        var list=activity.getRunningAppProcesses();
        JSONArray processes=new JSONArray();
        // Android documents null as the empty process-list result.
        if(list!=null) for(var process:list) {
            boolean fgs=process.importance==ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
            String state=process.importance>=ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED?"cached":
                    process.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND?"foreground":
                    process.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE && !fgs?"visible":"background";
            processes.put(new JSONObject().put("pid",process.pid).put("uid",process.uid).put("name",process.processName)
                    .put("importance",process.importance).put("state",state).put("foregroundService",fgs)
                    .put("packages",process.pkgList==null?new JSONArray():new JSONArray(process.pkgList)));
        }
        return new JSONObject().put("apps",ProcessGroups.group(processes,installed,activeTaskUids))
                .put("capturedAtElapsedMs",SystemClock.elapsedRealtime()).put("systemProcessCount",processes.length());
    }
}
