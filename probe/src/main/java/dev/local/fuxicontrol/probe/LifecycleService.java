package dev.local.fuxicontrol.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** No network or media: exercises service state and data-sync foreground exemption. */
public final class LifecycleService extends Service {
    @Override public IBinder onBind(Intent intent) {return new android.os.Binder();}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent.getBooleanExtra("foreground",false)) {
            var notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("cleanup-probe","Cleanup validation",NotificationManager.IMPORTANCE_LOW));
            startForeground(23,new Notification.Builder(this,"cleanup-probe").setContentTitle("栖序前台服务豁免测试")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        android.util.Log.i("FuxiProbe","Lifecycle service started "+startId);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {android.util.Log.i("FuxiProbe","Lifecycle service destroyed");super.onDestroy();}
}
