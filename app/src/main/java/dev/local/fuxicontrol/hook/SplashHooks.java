package dev.local.fuxicontrol.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;


import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import dev.local.fuxicontrol.core.SplashTargets;
import dev.local.fuxicontrol.service.SplashChannel;
import dev.local.fuxicontrol.service.Wire;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

/** Structurally checked splash entrypoints. Hook callbacks only read the local switch. */
final class SplashHooks {
    private final XposedModule module;
    private final String name;
    private final ClassLoader loader;
    private final List<XposedInterface.HookHandle> handles=new ArrayList<>();
    private final Handler worker;
    private final List<Runnable> pending=new ArrayList<>();
    private volatile boolean installed;
    private volatile long healthGeneration;
    private volatile boolean enabled;
    private volatile boolean connected;
    private volatile boolean matched;
    private volatile boolean failed;
    private long lastBlocked;
    SplashHooks(XposedModule module,String name,ClassLoader loader) {
        this.module=module;this.name=name;this.loader=loader;
        HandlerThread thread=new HandlerThread("qixu-splash-report");thread.start();worker=new Handler(thread.getLooper());
    }
    void start() {
        try {
            openWatch();
            if(!matched) {report("unsupported","应用签名不匹配");return;}
            switch(name) {
                case "tv.danmaku.bili" -> {
                    Class<?> order=type("tv.danmaku.bili.splash.ad.model.SplashOrder");
                    Method factory=method("tv.danmaku.bili.splash.ad.core.SplashManager","a",
                            type("tv.danmaku.bili.splash.ad.page.BaseSplash"),order);
                    // Both callers handle a missing page: cold start continues to home,
                    // and HotSplashActivity finishes. A missing order is not an ad hit.
                    install(factory,chain->{
                        if(!installed || !enabled || failed) return chain.proceed();
                        if(chain.getArg(0)==null) {
                            report("ready","开屏入口已调用，本次无广告订单");
                            return chain.proceed();
                        }
                        report("blocked",factory.getDeclaringClass().getName()+"."+factory.getName());
                        return null;
                    });
                    deoptimize(method("tv.danmaku.bili.MainActivitySplashComponentExtKt","d",boolean.class,
                            type("tv.danmaku.bili.MainActivityV2"),order,boolean.class));
                    deoptimize(method("tv.danmaku.bili.splash.ad.page.HotSplashActivity","onCreate",void.class,android.os.Bundle.class));
                    Class<?> source=type("kntr.srcs.app.splash.model.SplashSource");
                    // Obfuscated model names, newest first: 9.16 Cj1.*, 9.15 qj1.*.
                    Class<?> response=firstType("Cj1.y","qj1.o");
                    Class<?> selection=firstType("Cj1.a0","qj1.q");
                    Method select=method("kntr.srcs.app.splash.core.b","a",selection,
                            response,List.class,source,String.class,boolean.class);
                    // This release also ships the Kotlin splash pipeline. Its selection
                    // result is nullable; callers publish the normal no-order state.
                    install(select,chain->{
                        Object selected=chain.proceed();
                        if(!installed || !enabled || failed) return selected;
                        if(selected==null) report("ready","新版开屏入口已调用，本次无可展示订单");
                        else report("blocked",select.getDeclaringClass().getName()+"."+select.getName());
                        return null;
                    });
                    String component="kntr.srcs.app.splash.core.SplashOrderSelectComponent";
                    deoptimize(method(component,"b",selection,type(component),source,response));
                    deoptimize(method(component+"$startRealtimeShowSelect$1","invokeSuspend",Object.class,Object.class));
                }
                case "com.jingdong.app.mall" -> {
                    // The obfuscated name changes per release (V0 in 16.1.0, W0 in 16.1.50). The
                    // splash view check is the only private instance boolean method without parameters.
                    replace(unique(type("com.jingdong.app.mall.SplashFragment"),m->!Modifier.isStatic(m.getModifiers())
                            && Modifier.isPrivate(m.getModifiers()) && m.getReturnType()==boolean.class && m.getParameterCount()==0),chain->false);
                    deoptimize(method("com.jingdong.app.mall.SplashFragment","onCreateViews",android.view.View.class,android.view.LayoutInflater.class,android.os.Bundle.class));
                }
                case "com.autonavi.minimap" -> {
                    replace(method("com.autonavi.minimap.SplashFrequencyController","a",boolean.class,int.class,boolean.class),chain->false);
                    deoptimize(method("com.autonavi.minimap.g","o",void.class,int.class,String.class));
                }
                case "com.xingin.xhs" -> {
                    Class<?> callback=type("kotlin.jvm.functions.Function1");
                    Method invoke=callback.getMethod("invoke",Object.class);
                    if(invoke.getReturnType()!=Object.class) throw new NoSuchMethodException("Function1.invoke return type");
                    // The inspected loader uses callback #3 with integer zero for its no-ad branch.
                    // Result dispatcher, newest first: 9.52 xy.f, 9.49 az.f.
                    replace(method(firstType("xy.f","az.f").getName(),"a",void.class,type("re.o"),callback,callback),
                            chain->{invoke.invoke(chain.getArg(2),0);return null;});
                }
                case "com.taobao.idlefish" -> {
                    // Every sync, prefetch, preview and share-launch result reaches the adapters
                    // through this accessor. A null response is Xianyu's own no-ad branch:
                    // it dismisses the cover and ends the launch timeline.
                    String controller="com.taobao.fleamarket.splashad.SplashAdController";
                    Method success=method(controller,"-$$Nest$mhandleSuccessResponse",void.class,type(controller),
                            type("com.taobao.fleamarket.splashad.protocol.SplashAdResp"),String.class,boolean.class,android.app.Activity.class);
                    install(success,chain->{
                        if(!installed || !enabled || failed || chain.getArg(1)==null) return chain.proceed();
                        Object[] args=chain.getArgs().toArray();args[1]=null;
                        report("blocked",controller+".handleSuccessResponse");
                        return chain.proceed(args);
                    });
                }
                case "com.cainiao.wireless" -> {
                    // Cainiao skips splash requests for its "no ad request" list and routes on
                    // normally. The check is shared with home-page ads, so answer it only
                    // while the splash request is being decided.
                    Method request=method("com.cainiao.wireless.homepage.view.activity.WelcomeActivity",
                            "requestMamaAndRtbSplash",boolean.class,boolean.class);
                    // Obfuscated name as of 8.11.1023.
                    Method skip=method("com.cainiao.wireless.homepage.view.util.AdsUtil","azQ",boolean.class);
                    if(!Modifier.isStatic(skip.getModifiers())) throw new NoSuchMethodException("AdsUtil skip check is not static");
                    // The cold-start chain is small private calls that compiled onCreate can
                    // inline down to both hooked methods; keep every link interpreted.
                    String welcome=request.getDeclaringClass().getName();
                    deoptimize(request);
                    deoptimize(method(welcome,"onCreate",void.class,android.os.Bundle.class));
                    deoptimize(method(welcome,"onCreateImpl",void.class,android.os.Bundle.class));
                    deoptimize(method(welcome,"checkForcePermissionForApp",void.class));
                    deoptimize(method(welcome,"gotoMainActivityInternal",void.class));
                    deoptimize(method(welcome,"realGoHomeActivity",void.class));
                    ThreadLocal<Boolean> deciding=new ThreadLocal<>();
                    install(request,chain->{
                        deciding.set(Boolean.TRUE);
                        try {
                            Object requested=chain.proceed();
                            // Still set: preview or another early branch returned before the check.
                            if(installed && enabled && !failed && Boolean.TRUE.equals(deciding.get()))
                                report("ready","开屏判断已调用，本次未进入广告请求");
                            return requested;
                        } finally {deciding.remove();}
                    });
                    install(skip,chain->{
                        if(!installed || !enabled || failed || !Boolean.TRUE.equals(deciding.get())) return chain.proceed();
                        deciding.set(Boolean.FALSE);
                        report("blocked",request.getDeclaringClass().getName()+"."+request.getName());
                        return Boolean.TRUE;
                    });
                }
                case "com.taobao.taobao" -> {
                    // Taobao handles a denied interceptor itself: normal launcher on cold
                    // starts, and no splash on hot starts. Do not finish its host Activity.
                    replace(method("com.taobao.bootimage.arch.flow.bootimage.BroadcastFlowInterceptor",
                            "a",boolean.class,int.class),chain->false);
                }
                case "com.zhihu.android" -> {
                    Class<?> listener=type("com.zhihu.android.sdk.launchad.a");
                    Method noAd=method(listener.getName(),"a",void.class);
                    for(String entry:List.of("a","b"))
                        replace(method("com.zhihu.android.sdk.launchad.f",entry,void.class,Activity.class,listener),chain->{
                            Object callback=chain.getArg(1);
                            postCallback(noAd,callback);return null;
                        });
                    replace(method("com.zhihu.android.sdk.launchad.f","a",boolean.class,Context.class),chain->false);
                    replace(method("com.zhihu.android.app.util.dk","showLaunchAd",void.class,String.class),chain->null);
                }
                case "com.tencent.qqmusic" -> {
                    String sdk="com.tencentmusic.ad.integration.operationsplash.operationSplash.TMEOperationSplashAD";
                    Method listener=type(sdk).getMethod("getAdListener");
                    Class<?> listenerType=type("com.tencentmusic.ad.integration.operationsplash.operationSplash.controller.TMEOperSplashAdListener");
                    if(listener.getReturnType()!=listenerType) throw new NoSuchMethodException("getAdListener return type");
                    Class<?> errorType=type("com.tencentmusic.ad.integration.error.AdError");
                    Object error=errorType.getConstructor(int.class,String.class).newInstance(-1,"Splash disabled by Qixu");
                    Method noAd=listenerType.getMethod("onNoAD",errorType);
                    if(noAd.getReturnType()!=void.class) throw new NoSuchMethodException("onNoAD return type");
                    XposedInterface.Hooker deny=chain->{postCallback(noAd,listener.invoke(chain.getThisObject()),error);return null;};
                    replace(method(sdk,"fetchAdOnly",void.class),deny);
                    replace(method(sdk,"fetchAndShowIn",void.class,android.view.ViewGroup.class),deny);
                    replace(method(sdk,"showAd",void.class,android.view.ViewGroup.class,java.util.HashMap.class),deny);
                    replace(method(sdk,"showCustomSplash",void.class),deny);
                }
                default -> throw new IllegalStateException("Missing adapter");
            }
            install(Activity.class.getDeclaredMethod("onResume"),chain->{
                Object result=chain.proceed();
                if(!connected && !failed) worker.post(()->{
                    try {openWatch();} catch(IOException | JSONException e) {module.log(android.util.Log.WARN,"QixuSplash","Local service unavailable",e);}
                });
                return result;
            });
            // Resolve the complete adapter before installing any hook. Callbacks remain
            // passive until installation completes, including on other app threads.
            pending.forEach(Runnable::run);pending.clear();installed=true;
            report("ready",(handles.size()-1)+" 个开屏入口已加载");
            scheduleHealth();
        } catch(ReflectiveOperationException | LinkageError e) {
            failed=true;enabled=false;handles.forEach(XposedInterface.HookHandle::unhook);
            module.log(android.util.Log.ERROR,"QixuSplash","Adapter signature mismatch",e);
            report("unsupported",e.getClass().getSimpleName()+": "+e.getMessage());
        } catch(IOException | JSONException e) {
            enabled=false;module.log(android.util.Log.WARN,"QixuSplash","Local service unavailable; no ad hooks installed",e);
        }
    }
    private Class<?> type(String name) throws ClassNotFoundException {return Class.forName(name,false,loader);}
    private Method method(String owner,String name,Class<?> result,Class<?>... parameters) throws ReflectiveOperationException {
        Method method=type(owner).getDeclaredMethod(name,parameters);
        if(method.getReturnType()!=result) throw new NoSuchMethodException(owner+"."+name+" return type");
        method.setAccessible(true);return method;
    }
    private Class<?> firstType(String... names) throws ClassNotFoundException {
        for(String name:names) {
            try {return type(name);} catch(ClassNotFoundException next) { /* try the older name */ }
        }
        throw new ClassNotFoundException(String.join("|",names));
    }
    private static Method unique(Class<?> owner,java.util.function.Predicate<Method> filter) throws NoSuchMethodException {
        Method found=null;
        for(Method candidate:owner.getDeclaredMethods()) if(filter.test(candidate)) {
            if(found!=null) throw new NoSuchMethodException(owner.getName()+": ambiguous entrypoint");
            found=candidate;
        }
        if(found==null) throw new NoSuchMethodException(owner.getName()+": entrypoint missing");
        found.setAccessible(true);return found;
    }
    private void install(Method method,XposedInterface.Hooker hook) {
        pending.add(()->handles.add(module.hook(method).intercept(hook)));
    }
    private void deoptimize(Method method) { pending.add(()->module.deoptimize(method)); }
    private void replace(Method method,XposedInterface.Hooker action) {
        install(method,chain->{
            if(!installed || !enabled || failed) return chain.proceed();
            Object result=action.intercept(chain);
            report("blocked",method.getDeclaringClass().getName()+"."+method.getName());
            return result;
        });
    }
    private static void postCallback(Method method,Object listener,Object... args) {
        if(listener==null) return; // SDK preloading can omit the presentation listener.
        new Handler(Looper.getMainLooper()).post(()->{
            try {method.invoke(listener,args);}
            catch(ReflectiveOperationException e) {throw new IllegalStateException("Splash callback failed",e);}
        });
    }
    private Wire.Connection connect() throws IOException {var connection=Wire.splash();connection.setSoTimeout(500);return connection;}
    private synchronized void openWatch() throws IOException, JSONException {
        if(connected) return;
        // The first read runs on the app main thread before any splash code. The pipe
        // poll counts as network I/O for StrictMode; it is bounded by the 500 ms timeout.
        var policy=android.os.StrictMode.allowThreadDiskReads();
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX);
        try {openWatchLocked();} finally {android.os.StrictMode.setThreadPolicy(policy);}
    }
    private void openWatchLocked() throws IOException, JSONException {
        Wire.Connection socket=connect();
        try {
            Wire.send(socket,new JSONObject().put("command","watch").put("package",name));
            var reader=Wire.reader(socket);String first=reader.readLine();
            if(first==null) throw new IOException("Ad controller disconnected");
            update(new JSONObject(first));socket.setSoTimeout(0);connected=true;
            Thread watch=new Thread(()->{
                try(socket) {String line;while((line=reader.readLine())!=null) update(new JSONObject(line));}
                catch(IOException | JSONException e) {module.log(android.util.Log.WARN,"QixuSplash","Ad controller disconnected",e);}
                finally {enabled=false;connected=false;reconnect(1000);}
            },"qixu-splash-watch");
            watch.setDaemon(true);watch.start();
        } catch(IOException | JSONException | RuntimeException e) {socket.close();throw e;}
    }
    private void update(JSONObject config) throws JSONException {
        boolean previous=enabled;
        matched=config.getBoolean("matched");enabled=matched && config.getBoolean("enabled") && !failed;
        if(previous!=enabled) {
            healthGeneration++;
            if(installed && enabled) {report("ready","开屏拦截已恢复");scheduleHealth();}
        }
    }
    // A daemon restart closes every watch. Reconnect without waiting for the next
    // Activity resume, backing off while the service is unavailable.
    private void reconnect(long delay) {
        if(!installed || failed) return;
        worker.postDelayed(()->{
            if(connected || failed) return;
            try {openWatch();}
            catch(IOException | JSONException e) {reconnect(Math.min(delay*2,60_000));}
        },delay);
    }
    private void scheduleHealth() {
        long generation=healthGeneration;
        if(!enabled) return;
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            if(installed && connected && enabled && generation==healthGeneration)
                report("healthy","主进程持续运行 60 秒且主线程可响应");
        },dev.local.fuxicontrol.core.SplashHealth.STARTUP_MS);
    }
    private synchronized void report(String state,String detail) {
        // Count diagnostic batches, not impressions; cap work in a repeated SDK loop.
        long now=android.os.SystemClock.elapsedRealtime();
        // Only repeated hits are capped; a ready report must not hide the first hit.
        if(state.equals("blocked")) {
            if(now-lastBlocked<1000) return;
            lastBlocked=now;
        }
        worker.post(()->{
            try(Wire.Connection socket=connect()) {
                Wire.send(socket,new JSONObject().put("command","report").put("package",name).put("state",state)
                        .put("detail",detail.substring(0,Math.min(detail.length(),240))));
                if(Wire.reader(socket).readLine()==null) throw new IOException("Ad report not acknowledged");
            } catch(IOException | JSONException e) {module.log(android.util.Log.WARN,"QixuSplash","Cannot report ad status",e);}
        });
    }
}
