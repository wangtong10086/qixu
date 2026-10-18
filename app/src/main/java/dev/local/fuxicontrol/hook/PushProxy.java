package dev.local.fuxicontrol.hook;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import dev.local.fuxicontrol.service.Catalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Notification contents stay in system_server memory and never enter the audit stream. */
final class PushProxy {
    private static final ThreadLocal<String> starting=new ThreadLocal<>();
    static boolean permitsStart(String name) {return name.equals(starting.get());}
    private final Context context;
    private final NotificationManager notifications;
    private final Object notificationService;
    private final java.lang.reflect.Method createChannels;
    private final java.lang.reflect.Method notificationsEnabled;
    private final java.lang.reflect.Constructor<?> channelSlice;
    private final int managerUid;
    private final Map<String,Set<String>> tags=new HashMap<>();
    private final Map<String,Integer> counts=new HashMap<>();
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
    PushProxy(Context system) throws android.content.pm.PackageManager.NameNotFoundException,ReflectiveOperationException {
        context=system.createPackageContext(Catalog.MANAGER,Context.CONTEXT_IGNORE_SECURITY);
        notifications=context.getSystemService(NotificationManager.class);
        managerUid=context.getApplicationInfo().uid;
        var getService=NotificationManager.class.getDeclaredMethod("getService");getService.setAccessible(true);
        notificationService=getService.invoke(null);
        Class<?> api=Class.forName("android.app.INotificationManager");
        Class<?> slice=Class.forName("android.content.pm.ParceledListSlice");
        channelSlice=slice.getConstructor(java.util.List.class);
        createChannels=api.getMethod("createNotificationChannelsForPackage",String.class,int.class,slice);
        notificationsEnabled=api.getMethod("areNotificationsEnabledForPackage",String.class,int.class);
    }
    static String digest(String value) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    String show(JSONObject event,JSONObject settings,Set<Integer> foreground,Object activityManager) throws ReflectiveOperationException,org.json.JSONException,java.security.NoSuchAlgorithmException {
        String name=event.getString("package");
        JSONObject policy=settings.optJSONObject(name);
        if(policy==null || !policy.getBoolean("enabled")) return "disabled";
        ApplicationInfo app;
        try {app=context.getPackageManager().getApplicationInfo(name,0);}
        catch(android.content.pm.PackageManager.NameNotFoundException removed) {clear(name);return "removed";}
        if(foreground.contains(app.uid)) {clear(name);return "foreground";}
        boolean running=false;
        for(var process:context.getSystemService(ActivityManager.class).getRunningAppProcesses())
            if(process.uid==app.uid) {running=true;break;}
        if(policy.getBoolean("skipIfRunning") && running) return "already-running";
        if(!(boolean)notificationsEnabled.invoke(notificationService,Catalog.MANAGER,managerUid)) throw new SecurityException("Fuxi Control notification permission is disabled");
        long now=SystemClock.elapsedRealtime();
        seen.entrySet().removeIf(entry->now-entry.getValue()>300000);
        String message=event.getString("messageId");
        if(seen.containsKey(message)) return "duplicate";
        String sender=digest(name+":"+event.optString("from",""));
        String tag="proxy:"+sender;
        String channelId="push."+name+"."+policy.getBoolean("sound")+"."+policy.getBoolean("vibrate");
        String label=context.getPackageManager().getApplicationLabel(app).toString();
        var channel=new NotificationChannel(channelId,label,NotificationManager.IMPORTANCE_DEFAULT);
        if(!policy.getBoolean("sound")) channel.setSound(null,null);
        channel.enableVibration(policy.getBoolean("vibrate"));
        // Ordinary creation would attribute the channel to caller UID 1000.
        createChannels.invoke(notificationService,Catalog.MANAGER,managerUid,channelSlice.newInstance(java.util.List.of(channel)));
        int count=counts.merge(tag,1,Integer::sum);
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(name);
        var builder=new Notification.Builder(context,channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(label+" · "+count+" 条消息")
                .setContentText(policy.getBoolean("showContent")?event.optString("alert","收到新消息"):"收到新消息")
                .setNumber(count).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(policy.getBoolean("showContent")?Notification.VISIBILITY_PRIVATE:Notification.VISIBILITY_SECRET);
        if(launch!=null) builder.setContentIntent(PendingIntent.getActivity(context,sender.hashCode(),launch,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        notifications.notify(tag,1,builder.build());
        tags.computeIfAbsent(name,key->new HashSet<>()).add(tag);
        seen.put(message,now);if(seen.size()>1024) seen.remove(seen.keySet().iterator().next());
        if(policy.getBoolean("startOnPush") && !running && app.enabled) {
            // This is the framework's background process start, never an activity launch.
            var add=activityManager.getClass().getDeclaredMethod("addAppLocked",ApplicationInfo.class,String.class,boolean.class,String.class,int.class);
            add.setAccessible(true);
            starting.set(name);
            try {synchronized(activityManager) {add.invoke(activityManager,app,null,false,null,0);}}
            finally {starting.remove();}
        }
        return "posted";
    }
    void clear(String name) {
        for(String tag:tags.getOrDefault(name,Set.of())) {notifications.cancel(tag,1);counts.remove(tag);}
        tags.remove(name);
    }
}
