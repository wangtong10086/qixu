package dev.local.fuxicontrol.probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

public class NetworkService extends Service {
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String test=intent.getStringExtra("test");
        new Thread(()-> {
            try(Socket socket=new Socket()) {
                socket.setSendBufferSize(16384);socket.setReceiveBufferSize(16384);socket.setSoTimeout(25000);
                socket.connect(new InetSocketAddress("127.0.0.1",39182),3000);
                var out=new DataOutputStream(socket.getOutputStream());out.writeUTF(test);out.flush();
                long before=android.os.SystemClock.elapsedRealtime(),bytes=0;
                byte[] data=new byte[16384];
                if(test.equals("upload")) {
                    while(android.os.SystemClock.elapsedRealtime()-before<20000) {out.write(data);bytes+=data.length;}
                } else if(test.equals("download")) {
                    int count;while(android.os.SystemClock.elapsedRealtime()-before<20000 && (count=socket.getInputStream().read(data))!=-1) bytes+=count;
                } else throw new IllegalArgumentException("Unknown probe test");
                long elapsed=android.os.SystemClock.elapsedRealtime()-before;
                String result="{\"test\":\""+test+"\",\"bytes\":"+bytes+",\"elapsedMs\":"+elapsed+",\"bps\":"+(bytes*8000/Math.max(1,elapsed))+"}";
                java.nio.file.Files.write(getFilesDir().toPath().resolve("result.json"),result.getBytes(StandardCharsets.UTF_8));
                android.util.Log.i("FuxiProbe",result);
            } catch(Exception e) {android.util.Log.e("FuxiProbe","Network test failed",e);}
            finally {stopSelf(id);}
        },"NetworkProbe").start();
        return START_NOT_STICKY;
    }
}
