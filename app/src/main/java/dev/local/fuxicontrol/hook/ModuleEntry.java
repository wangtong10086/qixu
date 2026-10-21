package dev.local.fuxicontrol.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.os.Binder;
import dev.local.fuxicontrol.service.Wire;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/** All intercepted paths use an in-memory snapshot; no Binder or socket I/O under AMS locks. */
public final class ModuleEntry extends XposedModule {
    private volatile JSONObject snapshot=new JSONObject();
    private final Map<Integer,Integer> owners=new ConcurrentHashMap<>();
    private final Set<Integer> foreground=ConcurrentHashMap.newKeySet();
    private final Map<Integer,Long> lastForeground=new ConcurrentHashMap<>();
    private final Map<String,Integer> foregroundServices=new ConcurrentHashMap<>();
    private final Map<String,Boolean> installed=new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<JSONObject> events=new LinkedBlockingQueue<>(1024);
    private final Object stateLock=new Object();
    private volatile boolean connected;
    private volatile Object activityManager;
    private volatile PushProxy pushProxy;
    private final Object connectionLock=new Object();
    private boolean packageObserver;
    private android.os.PowerManager screenPower;

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String name=param.getPackageName();
        if(param.isFirstPackage() && name.equals(android.app.Application.getProcessName())
                && dev.local.fuxicontrol.core.SplashTargets.find(name)!=null)
            new SplashHooks(this,name,param.getClassLoader()).start();
    }

    @Override public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader loader=param.getClassLoader();
        install(loader,"startup","com.android.server.am.ProcessList","startProcessLocked",chain->{
            if(activityManager==null) activityManager=chain.getThisObject().getClass().getField("mService").get(chain.getThisObject());
            ApplicationInfo app=null;Object hosting=null;
            for(Object arg:chain.getArgs()) {
                if(arg instanceof ApplicationInfo value) app=value;
                else if(arg!=null && arg.getClass().getName().equals("com.android.server.am.HostingRecord")) hosting=arg;
            }
            if(app!=null && hosting!=null) {
                String kind=(String)hosting.getClass().getMethod("getType").invoke(hosting);
                if(!kind.equals("activity") && !kind.equals("top-activity") && !PushProxy.permitsStart(app.packageName) && blockedApp(app)) {
                    enqueue(emit("blocked",app.packageName,app.uid).put("reason",kind));
                    return null;
                }
            }
            return chain.proceed();
        },method->java.util.Arrays.asList(method.getParameterTypes()).contains(ApplicationInfo.class));
        installed.put("provider",Boolean.TRUE.equals(installed.get("startup")));
        install(loader,"process-started","com.android.server.am.ProcessList","handleProcessStartedLocked",chain->{
            Object result=chain.proceed();
            if((boolean)result) {
                Object process=chain.getArg(0);
                ApplicationInfo app=(ApplicationInfo)process.getClass().getField("info").get(process);
                int uid=process.getClass().getField("uid").getInt(process);
                int appId=uid%100000;
                int owner=appId>=20000 && appId<30000?uid-10000:app.uid;
                owners.put(uid,owner);
                enqueue(emit("process-started",app.packageName,uid).put("owner",owner));
            }
            return result;
        },method->method.getParameterCount()==5 && method.getParameterTypes()[1]==int.class);

        install(loader,"foreground","com.android.server.am.UidObserverController","enqueueUidChange",chain->{
            int uid=(int)chain.getArg(1);int state=(int)chain.getArg(3);
            boolean gone=((int)chain.getArg(2)&1)!=0; // UidRecord.CHANGE_GONE, Android 16.
            if(uid>=10000 && (state>=0 || gone)) synchronized(stateLock) {
                boolean active=!gone && state<=2;
                if(gone) {
                    foregroundServices.entrySet().removeIf(entry->entry.getValue()==uid);
                    owners.remove(uid);lastForeground.remove(uid);
                    enqueue(emit("process-gone","",uid));
                }
                boolean changed=active?foreground.add(uid):foreground.remove(uid);
                if(active) lastForeground.put(uid,android.os.SystemClock.elapsedRealtime());
                if(changed) {
                    String name=name(uid);
                    enqueue(emit("foreground",name,uid).put("active",active));
                    if(active) enqueue(emit("proxy-clear",name,uid));
                }
            }
            return chain.proceed();
        },method->method.getParameterCount()==8);
        install(loader,"wakelock","com.android.server.power.PowerManagerService","acquireWakeLockInternal",chain->{
            String tag=(String)chain.getArg(3),name=(String)chain.getArg(4);
            JSONObject policy=policy(name);
            if(enforcing() && policy!=null) {
                JSONArray tags=policy.getJSONArray("wakeLocks");
                for(int i=0;i<tags.length();i++) if(tags.getString(i).equals(tag)) {
                    enqueue(emit("blocked",name,policy.getInt("uid")).put("reason","wakelock"));return null;
                }
            }
            return chain.proceed();
        },method->method.getParameterCount()==10);
        install(loader,"jobs","com.android.server.job.JobSchedulerService","isReadyToBeExecutedLocked",chain->{
            Object job=chain.getArg(0);
            String name=(String)job.getClass().getMethod("getSourcePackageName").invoke(job);
            int uid=(int)job.getClass().getMethod("getSourceUid").invoke(job);
            return blockedBackground(name,uid)?false:chain.proceed();
        },method->method.getParameterCount()==1);
        install(loader,"push","com.android.server.am.ActiveServices","startServiceLocked",chain->{
            Intent intent=(Intent)chain.getArg(1);observePush(intent,(int)chain.getArg(4));
            ComponentName component=intent.getComponent();
            if(component!=null && !foreground.contains((int)chain.getArg(4))) {
                JSONObject p=policy(component.getPackageName());
                if(p!=null && blockedBackground(component.getPackageName(),p.getInt("uid"))) {
                    enqueue(emit("blocked",component.getPackageName(),p.getInt("uid")).put("reason","background-service"));return null;
                }
            }
            return chain.proceed();
        },method->method.getParameterCount()==10);
        install(loader,"push-broadcast","com.android.server.am.BroadcastController","broadcastIntentLocked",chain->{
            observePush((Intent)chain.getArg(3),(int)chain.getArg(18));return chain.proceed();
        },method->method.getParameterCount()==25);
        install(loader,"broadcast","com.android.server.am.BroadcastSkipPolicy","shouldSkipMessage",chain->{
            Object original=chain.proceed();if(original!=null || !enforcing()) return original;
            Object record=chain.getArg(0),receiver=chain.getArg(1);
            int caller=(int)record.getClass().getField("callingUid").get(record);
            if(foreground.contains(caller)) return null;
            String name;int uid;
            if(receiver instanceof android.content.pm.ResolveInfo resolved) {
                name=resolved.activityInfo.packageName;uid=resolved.activityInfo.applicationInfo.uid;
            } else {
                name=(String)receiver.getClass().getField("packageName").get(receiver);
                uid=(int)receiver.getClass().getField("owningUid").get(receiver);
            }
            if(blockedBackground(name,uid)) {
                enqueue(emit("blocked",name,uid).put("reason","background-broadcast"));
                return "Fuxi Control background broadcast policy";
            }
            return null;
        },method->method.getParameterCount()==2);
        install(loader,"task-removed","com.android.server.wm.ActivityTaskSupervisor","removeTask",chain->{
            Object result=chain.proceed();
            Object task=chain.getArg(0);
            Intent base=(Intent)task.getClass().getMethod("getBaseIntent").invoke(task);
            if(base!=null && base.getComponent()!=null) enqueue(emit("task-removed",base.getComponent().getPackageName(),-1));
            return result;
        },method->method.getParameterCount()==4);
        install(loader,"active-task","com.android.server.am.ActiveServices","setServiceForegroundInnerLocked",chain->{
            Object result=chain.proceed();
            Object service=chain.getArg(0);
            ApplicationInfo app=(ApplicationInfo)service.getClass().getField("appInfo").get(service);
            ComponentName component=(ComponentName)service.getClass().getField("name").get(service);
            int id=(int)chain.getArg(1),type=(int)chain.getArg(4);
            boolean userStarted=foreground.contains(app.uid) || android.os.SystemClock.elapsedRealtime()-lastForeground.getOrDefault(app.uid,-60000L)<30000;
            synchronized(stateLock) {
            if(id!=0 && (type & (1|2|8))!=0 && userStarted) foregroundServices.put(component.flattenToString(),app.uid);
            if(id==0) foregroundServices.remove(component.flattenToString());
            JSONObject event=emit("active-task",app.packageName,app.uid);
            event.put("active",foregroundServices.containsValue(app.uid));enqueue(event);
            }
            return result;
        },method->method.getParameterCount()==7);
        install(loader,"freezer","com.android.server.am.CachedAppOptimizer","freezeAppAsyncInternalLSP",chain->{
            Object process=chain.getArg(0);
            var infoField=process.getClass().getDeclaredField("info");infoField.setAccessible(true);
            ApplicationInfo info=(ApplicationInfo)infoField.get(process);
            JSONObject p=policy(info.packageName);
            if(enforcing() && p!=null) {
                if(p.getBoolean("resident")) return null;
                if(p.getString("background").equals("restricted") && !foreground.contains(info.uid) && !foregroundServices.containsValue(info.uid)) {
                    Object[] args=chain.getArgs().toArray();args[1]=0L;
                    return chain.proceed(args);
                }
            }
            return chain.proceed();
        },method->method.getParameterCount()==3);
        install(loader,"native-notification","com.android.server.notification.NotificationManagerService","enqueueNotificationInternal",chain->{
            Object result=chain.proceed();
            String name=(String)chain.getArg(0);
            JSONObject proxies=snapshot.optJSONObject("pushProxy");
            if((boolean)result && enforcing() && proxies!=null && proxies.has(name)) enqueue(emit("proxy-clear",name,-1));
            return result;
        },method->method.getReturnType()==boolean.class && method.getParameterCount()>0 && method.getParameterTypes()[0]==String.class &&
                java.util.Arrays.asList(method.getParameterTypes()).contains(android.app.Notification.class));
        new Thread(this::watch,"FuxiPolicy").start();
        new Thread(this::publish,"FuxiEvents").start();
    }
    private interface Interceptor {Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable;}
    private void install(ClassLoader loader,String feature,String className,String methodName,Interceptor interceptor,
                         java.util.function.Predicate<Method> select) {
        try {
            Class<?> type=Class.forName(className,false,loader);int count=0;
            for(Method method:type.getDeclaredMethods()) if(method.getName().equals(methodName)&&select.test(method)) {
                hook(method).setId("fuxi."+feature+"."+count).intercept(interceptor::intercept);count++;
            }
            installed.put(feature,count>0);
            android.util.Log.i("FuxiControl","Hook "+feature+": "+count);
        } catch(ClassNotFoundException e) {
            installed.put(feature,false);android.util.Log.e("FuxiControl","Missing system class: "+feature,e);
        }
    }
    private boolean enforcing() {return connected && snapshot.optString("mode").equals("enforce");}
    private JSONObject policy(String name) {
        JSONObject effective=snapshot.optJSONObject("effective");return effective==null?null:effective.optJSONObject(name);
    }
    private JSONObject backgroundPolicy(String name,int uid) {
        return backgroundPolicy(name,uid,policy(name));
    }
    private JSONObject backgroundPolicy(String name,int uid,JSONObject policy) {
        if(!enforcing() || policy==null || uid<10000 || foreground.contains(uid) || foregroundServices.containsValue(uid)) return null;
        if(android.os.SystemClock.elapsedRealtime()-lastForeground.getOrDefault(uid,-60000L)<30000) return null;
        if(foreground.contains(Binder.getCallingUid())) return null;
        return policy;
    }
    private boolean blockedApp(ApplicationInfo app) throws org.json.JSONException {
        JSONObject p=policy(app.packageName);
        if(p==null && snapshot.has("defaults")) {
            JSONObject config=new JSONObject().put("defaults",snapshot.getJSONObject("defaults")).put("apps",snapshot.getJSONObject("overrides"));
            p=dev.local.fuxicontrol.core.Policy.effective(config,app.packageName,(app.flags & ApplicationInfo.FLAG_SYSTEM)!=0);
        }
        p=backgroundPolicy(app.packageName,app.uid,p);
        return p!=null && p.getString("autostart").equals("deny");
    }
    private boolean blockedBackground(String name,int uid) throws org.json.JSONException {
        JSONObject p=backgroundPolicy(name,uid);
        return p!=null && (p.getString("autostart").equals("deny") || p.getString("background").equals("restricted"));
    }
    private String name(int uid) throws org.json.JSONException {
        JSONArray apps=snapshot.optJSONArray("apps");if(apps==null)return "";
        for(int i=0;i<apps.length();i++) if(apps.getJSONObject(i).getInt("uid")==uid) return apps.getJSONObject(i).getString("package");
        return "";
    }
    private JSONObject ownerSnapshot() throws org.json.JSONException {
        JSONObject result=new JSONObject();
        for(var owner:owners.entrySet()) result.put(Integer.toString(owner.getKey()),owner.getValue());
        return result;
    }
    private JSONObject emit(String type,String name,int uid) throws org.json.JSONException {
        JSONObject event=new JSONObject().put("command","event").put("type",type).put("package",name).put("uid",uid);
        // Queue a separate object only after callers finish adding their fields.
        return event;
    }
    private void observePush(Intent intent,int callerUid) throws org.json.JSONException {
        if(intent==null || !("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction()) ||
                "com.google.firebase.MESSAGING_EVENT".equals(intent.getAction()))) return;
        JSONObject gms=policy("com.google.android.gms");
        if(callerUid!=0 && callerUid!=1000 && (gms==null || callerUid!=gms.getInt("uid"))) return;
        String name=intent.getComponent()!=null?intent.getComponent().getPackageName():intent.getPackage();
        if(name==null) return;
        JSONObject settings=snapshot.optJSONObject("pushProxy");
        JSONObject channel=settings==null?null:settings.optJSONObject(name);
        boolean proxyEligible=enforcing() && channel!=null && channel.optBoolean("enabled");
        JSONObject event=emit("push-arrived",name,-1).put("proxyEligible",proxyEligible);
        if(!proxyEligible) {enqueue(event);return;}
        try {
            String id=intent.getStringExtra("google.message_id");
            if(id==null) id=intent.getStringExtra("message_id");
            String alert=intent.getStringExtra("alert"),from=intent.getStringExtra("from");
            if(alert!=null) event.put("alert",alert.substring(0,Math.min(4096,alert.length())));
            if(from!=null) event.put("from",from.substring(0,Math.min(1024,from.length())));
            event.put("messageId",name+":"+(id==null?PushProxy.digest(event.optString("alert")+event.optString("from")+android.os.SystemClock.elapsedRealtime()/1000):id));
        } catch(android.os.BadParcelableException malformed) {android.util.Log.w("FuxiControl","Invalid push extras");return;}
          catch(java.security.NoSuchAlgorithmException unavailable) {throw new AssertionError(unavailable);}
        enqueue(event);
    }
    private void enqueue(JSONObject event) {
        if(!events.offer(event)) android.util.Log.e("FuxiControl","Hook event queue full");
    }
    private void watch() {
        while(true) {
            try(LocalSocket socket=Wire.connect()) {
                Wire.send(socket,new JSONObject().put("command","watch"));
                var reader=Wire.reader(socket);String line;
                while((line=reader.readLine())!=null) {
                    snapshot=new JSONObject(line);
                    if(!packageObserver) {registerPackageObserver();packageObserver=true;}
                    connected=true;
                    synchronized(connectionLock) {connectionLock.notifyAll();}
                    synchronized(stateLock) {
                        enqueue(new JSONObject().put("command","event").put("type","hook-ready").put("hooks",new JSONObject(installed))
                            .put("interactive",screenPower.isInteractive()).put("revision",snapshot.getLong("revision")).put("owners",ownerSnapshot()).put("foreground",new JSONArray(foreground)).put("activeTasks",new JSONArray(new java.util.HashSet<>(foregroundServices.values()))));
                    }
                }
                connected=false;
            } catch(IOException|org.json.JSONException e) {
                connected=false;android.util.Log.e("FuxiControl","Policy service connection unavailable",e);
            }
            // The Magisk service starts after system_server; reconnect on boot and service restarts.
            android.os.SystemClock.sleep(2000);
        }
    }
    private void registerPackageObserver() {
        try {
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Object current=thread.getMethod("currentActivityThread").invoke(null);
            android.content.Context context=(android.content.Context)thread.getMethod("getSystemContext").invoke(current);
            screenPower=context.getSystemService(android.os.PowerManager.class);
            var screenFilter=new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx,Intent intent) {
                    try {enqueue(emit("screen-state","",-1).put("interactive",screenPower.isInteractive()));}
                    catch(org.json.JSONException invalid) {throw new IllegalStateException(invalid);}
                }
            },screenFilter,android.content.Context.RECEIVER_EXPORTED);
            installed.put("screen-state",true);
            android.content.BroadcastReceiver observer=new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx,Intent intent) {
                    try {enqueue(emit("package-changed","",intent.getIntExtra(Intent.EXTRA_UID,-1)));}
                    catch(org.json.JSONException invalid) {throw new IllegalStateException(invalid);}
                }
            };
            var packages=new android.content.IntentFilter();
            for(String action:new String[]{Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_CHANGED}) packages.addAction(action);
            packages.addDataScheme("package");context.registerReceiver(observer,packages,android.content.Context.RECEIVER_EXPORTED);
            var roles=new android.content.IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED);
            roles.addAction("android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED");
            roles.addAction("android.telecom.action.DEFAULT_DIALER_CHANGED");
            context.registerReceiver(observer,roles,android.content.Context.RECEIVER_EXPORTED);
            Class<?> listener=Class.forName("android.app.role.OnRoleHoldersChangedListener");
            Object roleListener=platformListener(listener,(proxy,method,args)-> {
                enqueue(emit("package-changed","",-1));return null;
            });
            var roleManager=context.getSystemService(android.app.role.RoleManager.class);
            roleManager.getClass().getMethod("addOnRoleHoldersChangedListenerAsUser",java.util.concurrent.Executor.class,listener,android.os.UserHandle.class)
                    .invoke(roleManager,context.getMainExecutor(),roleListener,android.os.UserHandle.getUserHandleForUid(0));
            Class<?> permissionListener=Class.forName("android.content.pm.PackageManager$OnPermissionsChangedListener");
            Object permissionObserver=platformListener(permissionListener,(proxy,method,args)-> {
                enqueue(emit("permissions-changed","",(int)args[0]));return null;
            });
            context.getPackageManager().getClass().getMethod("addOnPermissionsChangeListener",permissionListener)
                    .invoke(context.getPackageManager(),permissionObserver);
            installed.put("permissions-observer",true);
            pushProxy=new PushProxy(context);
            installed.put("push-proxy",true);
            installed.put("package-observer",true);
        } catch(android.content.pm.PackageManager.NameNotFoundException removed) {throw new IllegalStateException("Manager package is missing",removed);}
          catch(ReflectiveOperationException failure) {throw new IllegalStateException("Cannot register package observer",failure);}
    }
    private static Object platformListener(Class<?> type,java.lang.reflect.InvocationHandler callback) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                case "toString" -> "FuxiObserver:"+type.getSimpleName();
                default -> throw new IllegalStateException(method.getName());
            };
            return callback.invoke(proxy,method,args);
        });
    }
    private void publish() {
        while(true) {
            try {
                JSONObject event=events.take();
                synchronized(connectionLock) {while(!connected) connectionLock.wait();}
                if(event.getString("type").equals("proxy-clear")) {pushProxy.clear(event.getString("package"));continue;}
                if(event.getString("type").equals("push-arrived")) {
                    if(enforcing() && event.getBoolean("proxyEligible")) {
                        try {event.put("proxyState",pushProxy.show(event,snapshot.getJSONObject("pushProxy"),foreground,activityManager));}
                        catch(ReflectiveOperationException|java.security.NoSuchAlgorithmException|SecurityException failure) {
                            installed.put("push-proxy",false);
                            android.util.Log.e("FuxiControl","Push proxy failed",failure);
                            Wire.call(new JSONObject().put("command","event").put("type","feature-error").put("feature","push-proxy"));
                        }
                    }
                    event.remove("alert");event.remove("from");
                }
                Wire.call(event);
            }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return;}
            catch(IOException|org.json.JSONException failure) {android.util.Log.e("FuxiControl","Event delivery failed",failure);}
        }
    }
    @Override public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {return false;}
}
