package dev.local.fuxicontrol.service;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import dev.local.fuxicontrol.core.Policy;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Catalog {
    public static final String MANAGER = "dev.local.fuxicontrol";
    private final Context context;
    private final PackageManager pm;
    private JSONArray cachedApps;
    public synchronized void invalidate() {cachedApps=null;}
    public Catalog(Context context) { this.context = context; pm = context.getPackageManager(); }
    @SuppressWarnings("unchecked")
    public Set<String> protectedPackages() throws Exception {
        Set<String> result = new HashSet<>(Set.of(MANAGER,"android","com.android.systemui",
                "com.google.android.gms","com.android.vending","com.google.android.apps.walletnfcrel",
                "com.miui.tsmclient","com.xiaomi.xmsf","com.tencent.mm",
                "com.airstarbank.mobilebanking","com.ifast.gb","com.ifast.fsm","com.kikoff"));
        // An app_process root service has no registered ApplicationThread. The settings
        // shell command uses getContentProviderExternal, the supported shell contract.
        String ime = Shell.run("settings","get","secure","default_input_method").trim();
        if (ime != null && ime.contains("/")) result.add(ime.substring(0,ime.indexOf('/')));
        RoleManager roles = context.getSystemService(RoleManager.class);
        for (String role : new String[]{"android.app.role.HOME","android.app.role.SMS","android.app.role.DIALER","android.app.role.WALLET"})
            if (roles.isRoleAvailable(role)) result.addAll((java.util.List<String>)RoleManager.class.getMethod("getRoleHolders",String.class).invoke(roles,role));
        for(var service:pm.queryIntentServices(new android.content.Intent("android.net.VpnService"),PackageManager.MATCH_ALL))
            result.add(service.serviceInfo.packageName);
        return result;
    }
    public synchronized JSONArray applications() throws Exception {
        if(cachedApps!=null) return cachedApps;
        Set<String> protectedNames = protectedPackages();
        JSONArray result = new JSONArray();
        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.uid < 10000) continue;
            // A VPN is part of the current connectivity path; exclude it from inherited restrictions.
            result.put(new JSONObject().put("package",app.packageName).put("uid",app.uid)
                    .put("label",pm.getApplicationLabel(app).toString()).put("system",(app.flags & ApplicationInfo.FLAG_SYSTEM)!=0)
                    .put("protected",protectedNames.contains(app.packageName) || (app.flags & ApplicationInfo.FLAG_SYSTEM)!=0));
        }
        cachedApps=result;
        return result;
    }
    public JSONObject snapshot(JSONObject config) throws Exception {
        JSONArray apps = applications();
        Set<Integer> protectedUids = new HashSet<>();
        for(int i=0;i<apps.length();i++) if(apps.getJSONObject(i).getBoolean("protected")) protectedUids.add(apps.getJSONObject(i).getInt("uid"));
        JSONObject effective = new JSONObject();
        for (int i=0;i<apps.length();i++) {
            JSONObject app = apps.getJSONObject(i);
            String name = app.getString("package");
            app.put("protected",protectedUids.contains(app.getInt("uid")));
            JSONObject p = Policy.effective(config,name,app.getBoolean("protected"));
            p.put("uid",app.getInt("uid"));
            effective.put(name,p);
        }
        // Resources, runtime grants and UID AppOps affect all shared-UID siblings.
        java.util.Map<Integer,JSONObject> uidOverrides=new java.util.HashMap<>();
        java.util.Map<Integer,JSONObject> uidSources=new java.util.HashMap<>();
        Set<String> resourceFields=Set.of("cpuAffinity","cpuNice","uclampMax","memoryHighMiB","memoryMaxMiB","uploadBps","downloadBps","burstBytes","network","permissions","appOps");
        for(int i=0;i<apps.length();i++) {
            JSONObject app=apps.getJSONObject(i);String name=app.getString("package");int uid=app.getInt("uid");
            JSONObject overrides=config.getJSONObject("apps").optJSONObject(name);
            if(overrides==null) continue;
            JSONObject values=uidOverrides.computeIfAbsent(uid,k->new JSONObject());
            JSONObject sources=uidSources.computeIfAbsent(uid,k->new JSONObject());
            for(String field:resourceFields) if(overrides.has(field)) {
                if(field.equals("cpuAffinity")) {
                    JSONObject merged=values.optJSONObject(field);if(merged==null)merged=new JSONObject();
                    JSONObject phases=overrides.getJSONObject(field);
                    for(String phase:dev.local.fuxicontrol.core.Json.keys(phases)) {
                        if(merged.has(phase)&&!merged.getString(phase).equals(phases.getString(phase)))throw new IllegalArgumentException("Conflicting shared UID affinity: "+name+" / "+phase);
                        merged.put(phase,phases.getString(phase));sources.put(field+"."+phase,"shared-uid:"+name);
                    }
                    values.put(field,merged);
                } else {
                    if(values.has(field) && !values.get(field).toString().equals(overrides.get(field).toString()))
                        throw new IllegalArgumentException("Conflicting shared UID setting: "+name+" / "+field);
                    values.put(field,overrides.get(field));
                }
                sources.put(field,"shared-uid:"+name);
            }
        }
        for(int i=0;i<apps.length();i++) {
            JSONObject app=apps.getJSONObject(i);int uid=app.getInt("uid");
            JSONObject overrides=uidOverrides.get(uid);
            if(overrides==null) continue;
            JSONObject p=effective.getJSONObject(app.getString("package"));
            for(String field:dev.local.fuxicontrol.core.Json.keys(overrides)) {
                if(field.equals("cpuAffinity")) {
                    JSONObject base=dev.local.fuxicontrol.core.AffinityPolicy.merge(dev.local.fuxicontrol.core.AffinityPolicy.defaults(),config.getJSONObject("defaults").getJSONObject("cpuAffinity"));
                    p.put(field,dev.local.fuxicontrol.core.AffinityPolicy.merge(base,overrides.getJSONObject(field)));
                    for(String phase:dev.local.fuxicontrol.core.Json.keys(overrides.getJSONObject(field))) {
                        String source=uidSources.get(uid).getString(field+"."+phase);
                        p.getJSONObject("sources").put(field+"."+phase,source.equals("shared-uid:"+app.getString("package"))?"app":source);
                    }
                } else p.put(field,overrides.get(field));
                String source=uidSources.get(uid).getString(field);
                p.getJSONObject("sources").put(field,source.equals("shared-uid:"+app.getString("package"))?"app":source);
            }
        }
        JSONObject proxies=new JSONObject();
        JSONObject configured=config.optJSONObject("pushProxy");
        if(configured!=null) for(String name:dev.local.fuxicontrol.core.Json.keys(configured)) proxies.put(name,Policy.pushProxy(config,name));
        return new JSONObject().put("pushProxy",proxies).put("defaults",config.getJSONObject("defaults")).put("overrides",config.getJSONObject("apps"))
                .put("revision",config.getLong("revision")).put("mode",config.getString("mode"))
                .put("apps",apps).put("effective",effective).put("rules",config.getJSONArray("rules"));
    }
    public boolean managerUid(int uid, String expectedSigner) throws Exception {
        PackageInfo info = pm.getPackageInfo(MANAGER,PackageManager.GET_SIGNING_CERTIFICATES);
        if (uid != info.applicationInfo.uid) return false;
        String signer = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()));
        return signer.equals(expectedSigner);
    }
    public Context context() { return context; }
}
