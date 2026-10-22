package dev.local.fuxicontrol.service;

import android.content.AttributionSource;
import android.content.Context;
import android.os.*;
import java.io.IOException;

/** External provider acquisition is required for root app_process without an AMS app record. */
final class BrokerConnection implements AutoCloseable {
    private final IBinder token=new Binder();
    private Object manager;
    private Object provider;
    Bundle call(String method,Bundle extras) throws ReflectiveOperationException,IOException {
        int uid=android.os.Process.myUid();
        if(uid==1000) {
            String boot=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"sys.boot_completed");
            if(!boot.equals("1")) throw new IOException("System bootstrap has not completed");
        }
        if(uid==0 || uid==1000) {
            if(provider==null) {
                manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
                Object holder=Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",
                        String.class,int.class,IBinder.class,String.class).invoke(manager,BrokerProvider.AUTHORITY,0,token,"QixuRoot");
                if(holder==null) throw new IOException("栖序 IPC 提供器不可用");
                provider=holder.getClass().getField("provider").get(holder);
            }
            return (Bundle)Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,
                    String.class,String.class,String.class,Bundle.class).invoke(provider,
                    new AttributionSource.Builder(uid).setPackageName("android").build(),BrokerProvider.AUTHORITY,method,null,extras);
        }
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Context context=(Context)thread.getMethod("currentApplication").invoke(null);
        if(context==null) context=(Context)thread.getMethod("getSystemContext").invoke(thread.getMethod("currentActivityThread").invoke(null));
        return context.getContentResolver().call(android.net.Uri.parse("content://"+BrokerProvider.AUTHORITY),method,null,extras);
    }
    void awaitDeath() throws ReflectiveOperationException,IOException,InterruptedException {
        IBinder binder=(IBinder)Class.forName("android.os.IInterface").getMethod("asBinder").invoke(provider);
        var death=new java.util.concurrent.CountDownLatch(1);
        try {binder.linkToDeath(death::countDown,0);} catch(RemoteException e) {throw new IOException(e);}
        death.await();
    }
    @Override public void close() throws ReflectiveOperationException {
        if(provider!=null) {
            Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class)
                    .invoke(manager,BrokerProvider.AUTHORITY,token,0);
            provider=null;
        }
    }
}
