package dev.local.fuxicontrol.probe;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
public final class AffinityWorker extends Service {
    private volatile boolean running;
    private volatile boolean spawnChild,renameWorker;
    private void work(String name,boolean children) {
        new Thread(()->{
            long began=android.os.SystemClock.elapsedRealtime();boolean spawned=false,renamed=false;double value=1;
            while(running&&android.os.SystemClock.elapsedRealtime()-began<600000) {
                for(int i=0;i<20000;i++)value=Math.sin(value+i);
                if(children&&!spawned&&spawnChild){spawned=true;work("LateChild",false);}
                if(children&&!renamed&&renameWorker){renamed=true;Thread.currentThread().setName("RenamedWorker");}
                try{Thread.sleep(40);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}
            }
            android.util.Log.i("FuxiAffinityProbe","worker stopped "+value);
        },name).start();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(!running){running=true;work("RenderProbe",false);work("worker-1",true);}
        if(intent!=null){spawnChild|=intent.getBooleanExtra("spawn",false);renameWorker|=intent.getBooleanExtra("rename",false);}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){running=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return new android.os.Binder();}
}
