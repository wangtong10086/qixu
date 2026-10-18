package dev.local.fuxicontrol.probe;

public class Boot extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context context,android.content.Intent intent) {
        android.util.Log.i("FuxiProbe","Broadcast delivered");
    }
}
