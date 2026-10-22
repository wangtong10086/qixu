package dev.local.fuxicontrol.probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Bounded synthetic CPU, resident memory and disk IO in a separate app process. */
public class ResourceWorker extends Service {
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        new Thread(()-> {
            long until=android.os.SystemClock.elapsedRealtime()+60000;
            byte[] memory=new byte[16*1024*1024];
            for(int i=0;i<memory.length;i+=4096)memory[i]=1;
            try {
                java.nio.file.Path file=getFilesDir().toPath().resolve("resource-worker.bin");
                try(var output=new java.io.FileOutputStream(file.toFile())) {output.write(memory);output.getFD().sync();}
                double value=1;
                while(android.os.SystemClock.elapsedRealtime()<until) {
                    for(int i=0;i<100000;i++)value=Math.sin(value+i)+memory[i%memory.length];
                    Thread.sleep(10);
                }
                java.nio.file.Files.delete(file);
                android.util.Log.i("FuxiProbe","resource worker complete "+value);
            } catch(java.io.IOException|InterruptedException failure) {android.util.Log.e("FuxiProbe","Resource fixture failed",failure);}
            finally {stopSelf(id);}
        },"ResourceFixture").start();
        return START_NOT_STICKY;
    }
}
