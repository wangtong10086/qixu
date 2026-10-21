package dev.local.fuxicontrol.service;

import android.content.pm.PackageManager;
import android.content.Context;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import dev.local.fuxicontrol.core.SplashHealth;
import dev.local.fuxicontrol.core.SplashTargets;
import java.nio.file.Files;
import java.nio.file.Path;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import dev.local.fuxicontrol.core.Policy;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate, unprivileged channel: an app can only read its own ad switch and report diagnostics. */
public final class SplashChannel {
    public static final String ADDRESS="qixu.splash.v1";
    private final Store store;
    private final PackageManager pm;
    private final Map<String,JSONObject> reports=new ConcurrentHashMap<>();
    private final Map<String,Thread> watchers=new ConcurrentHashMap<>();
    private final ActivityManager activity;
    private final SplashHealth health;
    private final Path healthFile;
    private final String boot;
    private String savedHealth;
    private volatile long healthEpoch;
    private final Map<String,Integer> peers=new ConcurrentHashMap<>();
    public SplashChannel(Store store, Context context) throws Exception {
        this.store=store;this.pm=context.getPackageManager();
        activity=context.getSystemService(ActivityManager.class);
        healthFile=store.directory.resolve("splash-health.json");
        health=new SplashHealth(Files.exists(healthFile)?new JSONObject(Text.read(healthFile)):new JSONObject());
        savedHealth=health.saved().toString();
        boot=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
    }
    private String installation(JSONObject info) throws Exception {
        return info.getLong("version")+":"+info.getLong("updated")+":"+SplashTargets.ADAPTER_REVISION;
    }
    private void saveHealth() throws Exception {
        String value=health.saved().toString();
        if(!value.equals(savedHealth)) {
            Store.atomic(healthFile,value);savedHealth=value;healthEpoch++;
            synchronized(store) {store.notifyAll();}
        }
    }
    private synchronized void beginWatch(String name,JSONObject info,int pid) throws Exception {
        String key=installation(info);
        JSONObject previous=health.session(name,key);
        if(previous!=null && !previous.getBoolean("settled") && previous.getString("boot").equals(boot)
                && previous.getInt("pid")!=pid) {
            for(ApplicationExitInfo exit:activity.getHistoricalProcessExitReasons(name,previous.getInt("pid"),8)) {
                if(!name.equals(exit.getProcessName())) continue;
                int code=exit.getReason();
                SplashHealth.Exit reason=SplashHealth.Exit.OTHER;
                if(code==ApplicationExitInfo.REASON_CRASH || code==ApplicationExitInfo.REASON_CRASH_NATIVE)
                    reason=SplashHealth.Exit.CRASH;
                else if(code==ApplicationExitInfo.REASON_ANR) reason=SplashHealth.Exit.ANR;
                health.exit(name,key,exit.getPid(),boot,exit.getTimestamp(),reason);
            }
        }
        health.begin(name,key,pid,boot,System.currentTimeMillis());saveHealth();
    }
    private synchronized JSONObject healthStatus(String name,JSONObject info) throws Exception {
        return health.status(name,installation(info));
    }
    private synchronized void disarm(String name,JSONObject info,int pid) throws Exception {
        health.disarm(name,installation(info),pid);saveHealth();
    }
    public synchronized JSONObject retry(String name,boolean dryRun) throws Exception {
        JSONObject info=identity(name);
        if(!info.getBoolean("installed")) throw new IllegalArgumentException("应用未安装");
        if(!info.getBoolean("matched")) throw new IllegalArgumentException("应用签名不匹配");
        if(!dryRun) {
            health.retry(name,installation(info));reports.remove(name);saveHealth();
        }
        return new JSONObject().put("package",name).put("dryRun",dryRun).put("retry",true);
    }
    private synchronized void healthReport(String name,JSONObject info,int pid,String state) throws Exception {
        if(!snapshot(name,store.get()).getBoolean("enabled")) return;
        String key=installation(info);long now=System.currentTimeMillis();
        if(state.equals("ready")) health.arm(name,key,pid,boot,now);
        else if(state.equals("healthy")) health.healthy(name,key,pid,boot,now);
        saveHealth();
    }
    public JSONObject identity(String name) throws Exception {
        var target=SplashTargets.find(name);
        if(target==null) throw new IllegalArgumentException("没有开屏适配器："+name);
        try {
            var info=pm.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);
            var signatures=info.signingInfo.getApkContentsSigners();
            String certificate=signatures.length==1?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())):"";
            return new JSONObject().put("package",name).put("installed",true).put("uid",info.applicationInfo.uid)
                    .put("version",info.getLongVersionCode()).put("versionName",info.versionName)
                    .put("updated",info.lastUpdateTime).put("inspectedVersion",target.version())
                    .put("crossVersion",info.getLongVersionCode()!=target.version())
                    .put("matched",target.matches(info.getLongVersionCode(),certificate));
        } catch(PackageManager.NameNotFoundException e) {
            return new JSONObject().put("package",name).put("installed",false).put("matched",false);
        }
    }
    private JSONObject snapshot(String name, JSONObject config) throws Exception {
        JSONObject item=identity(name);
        JSONObject policy=config.getJSONObject("apps").optJSONObject(name);
        boolean requested=policy!=null && policy.optBoolean("blockSplashAds",false);
        JSONObject state=item.getBoolean("installed")?healthStatus(name,item):new JSONObject().put("suspended",false).put("suspendReason","");
        item.put("suspended",state.getBoolean("suspended")).put("suspendReason",state.getString("suspendReason"));
        return item.put("requested",requested).put("enabled",requested && item.getBoolean("matched") && !state.getBoolean("suspended") && config.getString("mode").equals("enforce"))
                .put("mode",config.getString("mode")).put("revision",config.getLong("revision"));
    }
    private String appliedScope;
    public synchronized void reconcileScope(JSONObject config) throws Exception {
        var desired=new java.util.TreeSet<String>();
        JSONObject apps=config.getJSONObject("apps");
        for(var target:SplashTargets.ALL) {
            JSONObject app=apps.optJSONObject(target.packageName());
            if(app!=null && app.optBoolean("blockSplashAds",false) && identity(target.packageName()).getBoolean("installed"))
                desired.add(target.packageName());
        }
        String fingerprint=desired.toString();
        if(fingerprint.equals(appliedScope)) return;
        var current=new java.util.HashSet<String>();
        JSONObject listed=vector("ls");
        var data=listed.getJSONArray("data");
        for(int i=0;i<data.length();i++) if(data.getJSONObject(i).getInt("USER_ID")==0)
            current.add(data.getJSONObject(i).getString("APP_PACKAGE"));
        for(String name:desired) if(!current.contains(name)) vector("add",name+"/0");
        for(var target:SplashTargets.ALL) if(current.contains(target.packageName()) && !desired.contains(target.packageName()))
            vector("rm",target.packageName()+"/0");
        appliedScope=fingerprint;
    }
    private JSONObject vector(String action,String... packages) throws Exception {
        var args=new java.util.ArrayList<String>(java.util.List.of("/system/bin/env",
                "CLASSPATH=/data/adb/modules/zygisk_vector/daemon.apk","/system/bin/app_process","/system/bin",
                "org.matrix.vector.daemon.Cli","scope",action,Catalog.MANAGER,"--json"));
        args.addAll(java.util.List.of(packages));
        JSONObject result=new JSONObject(Shell.run(args.toArray(String[]::new)));
        if(!result.getBoolean("success")) throw new IOException("Vector scope change rejected: "+result);
        return result;
    }
    public JSONObject status() throws Exception {
        JSONArray items=new JSONArray();JSONObject config=store.get();
        for(var target:SplashTargets.ALL) {
            String name=target.packageName();
            JSONObject item=snapshot(name,config).put("connected",watchers.containsKey(name));
            JSONObject report=reports.get(name);
            if(report!=null && item.getBoolean("installed") && installation(item).equals(report.optString("installation"))) item.put("report",Policy.copy(report));
            items.put(item);
        }
        return new JSONObject().put("apps",items);
    }
    public void start() throws IOException {
        Native.socketLabel(true);
        LocalServerSocket server;
        try {server=new LocalServerSocket(ADDRESS);} finally {Native.socketLabel(false);}
        Semaphore slots=new Semaphore(16);
        Thread accept=new Thread(()->{
            try(server) {
                while(true) {
                    LocalSocket socket=server.accept();
                    if(!slots.tryAcquire()) {socket.close();continue;}
                    new Thread(()->{
                        String watching=null;
                        try(socket) {
                            socket.setSoTimeout(1000);
                            var reader=Wire.reader(socket);StringBuilder input=new StringBuilder();int ch;
                            while((ch=reader.read())!=-1 && ch!='\n') {
                                if(input.length()>=4096) throw new IllegalArgumentException("Ad request too large");
                                input.append((char)ch);
                            }
                            JSONObject request=new JSONObject(input.toString());String name=request.getString("package");
                            JSONObject info=identity(name);
                            if(!info.getBoolean("installed") || info.getInt("uid")!=socket.getPeerCredentials().getUid())
                                throw new SecurityException("Ad peer does not own package");
                            switch(request.getString("command")) {
                                case "watch" -> {
                                    watching=name;
                                    int pid=socket.getPeerCredentials().getPid();
                                    beginWatch(name,info,pid);peers.put(name,pid);
                                    Thread previous=watchers.put(name,Thread.currentThread());
                                    if(previous!=null) previous.interrupt();
                                    socket.setSoTimeout(0);
                                    Thread writer=Thread.currentThread();
                                    Thread disconnect=new Thread(()->{
                                        try {
                                            // The watch protocol has no further client messages. EOF must
                                            // wake a writer even when the policy has not changed.
                                            reader.read();
                                        } catch(IOException e) {
                                            android.util.Log.d("QixuSplash","Watch closed: "+name);
                                        } finally {writer.interrupt();}
                                    },"qixu-splash-disconnect");
                                    disconnect.setDaemon(true);disconnect.start();
                                    long revision=-1,epoch=-1;
                                    while(!Thread.currentThread().isInterrupted()) {
                                        JSONObject config;
                                        synchronized(store) {
                                            config=store.get();
                                            if(revision==config.getLong("revision") && epoch==healthEpoch) {
                                                store.wait();continue;
                                            }
                                        }
                                        long nextEpoch=healthEpoch;
                                        JSONObject state=snapshot(name,config);
                                        if(!state.getBoolean("enabled")) disarm(name,info,pid);
                                        Wire.send(socket,state);
                                        revision=config.getLong("revision");epoch=nextEpoch;
                                    }
                                }
                                case "report" -> {
                                    int pid=socket.getPeerCredentials().getPid();
                                    if(!Integer.valueOf(pid).equals(peers.get(name))) throw new SecurityException("Ad report must belong to the watching process");
                                    String state=request.getString("state");Policy.oneOf(state,"ready","blocked","unsupported","error","healthy");
                                    healthReport(name,info,pid,state);
                                    String detail=request.optString("detail","");
                                    if(detail.length()>240) throw new IllegalArgumentException("Ad detail too long");
                                    if(!state.equals("healthy")) synchronized(reports) {
                                        JSONObject old=reports.get(name);
                                        reports.put(name,new JSONObject().put("state",state).put("detail",detail).put("installation",installation(info))
                                            .put("at",System.currentTimeMillis()).put("blocked",(old==null?0:old.optLong("blocked"))+(state.equals("blocked")?1:0)));
                                    }
                                    Wire.send(socket,new JSONObject().put("ok",true));
                                }
                                default -> throw new IllegalArgumentException("Unknown ad command");
                            }
                        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                        catch(Exception e) {android.util.Log.w("QixuSplash","Ad channel: "+e.getMessage());}
                        finally {if(watching!=null) watchers.remove(watching,Thread.currentThread());slots.release();}
                    },"qixu-splash-client").start();
                }
            } catch(IOException e) {throw new java.io.UncheckedIOException(e);}
        },"qixu-splash-server");
        accept.setDaemon(true);accept.start();
    }
}
