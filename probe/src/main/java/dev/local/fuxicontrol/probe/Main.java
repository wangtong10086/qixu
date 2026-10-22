package dev.local.fuxicontrol.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public class Main extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TextView text=new TextView(this);text.setText("Fuxi validation probe");setContentView(text);
        if(getIntent().getBooleanExtra("socket",false)) new Thread(()-> {
            try(var socket=new android.net.LocalSocket()) {
                socket.connect(new android.net.LocalSocketAddress("fuxicontrol.v1"));
                socket.setSoTimeout(3000);
                if(socket.getPeerCredentials().getUid()!=0) throw new SecurityException("Unexpected server UID");
                int response;
                try {
                    socket.getOutputStream().write("{\"command\":\"status\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    response=socket.getInputStream().read();
                }
                catch(java.io.IOException reset) {
                    if(!reset.getMessage().contains("reset") && !reset.getMessage().equals("Broken pipe")) throw reset;
                    response=-1;
                }
                String result="{\"unauthorizedDenied\":"+(response==-1)+"}";
                java.nio.file.Files.write(getFilesDir().toPath().resolve("auth.json"),result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch(Exception e) {android.util.Log.e("FuxiProbe","Authorization test failed",e);}
        }).start();
        if(getIntent().getBooleanExtra("isolated",false)) bindService(new Intent(this,Isolated.class),new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name,android.os.IBinder service) {
                var data=android.os.Parcel.obtain();var reply=android.os.Parcel.obtain();
                try {
                    service.transact(1,data,reply,0);reply.readException();int uid=reply.readInt();
                    java.nio.file.Files.write(getFilesDir().toPath().resolve("isolated.json"),("{\"uid\":"+uid+",\"owner\":"+android.os.Process.myUid()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch(Exception failure) {android.util.Log.e("FuxiProbe","Isolated probe failed",failure);}
                finally {data.recycle();reply.recycle();}
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        },BIND_AUTO_CREATE);
        if(getIntent().hasExtra("udp")) Datagrams.run(this,getIntent().getStringExtra("udp"),getIntent().getBooleanExtra("ipv6",false));
        if(getIntent().getBooleanExtra("resources",false))startService(new Intent(this,ResourceWorker.class));
        if(getIntent().hasExtra("test")) startService(new Intent(this,NetworkService.class).putExtras(getIntent()));
    }
}
