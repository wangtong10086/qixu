package dev.local.fuxicontrol.service;

import java.util.ArrayList;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Run with app_process as root and the installed controller on CLASSPATH. Read-only. */
public final class IpcStress {
    public static void main(String[] args) throws Exception {
        var watches=new ArrayList<Wire.Connection>();
        try {
            for(int i=0;i<8;i++) {
                Wire.Connection c=Wire.connect();watches.add(c);
                Wire.send(c,new JSONObject().put("command","watch"));
                JSONObject snapshot=new JSONObject(Wire.reader(c).readLine());
                if(!snapshot.has("revision"))throw new AssertionError("Watch was not admitted");
            }
            try {
                Wire.call(new JSONObject().put("command","status"));
                throw new AssertionError("Ninth session should be rejected");
            } catch(Wire.CommandException expected) {
                if(!expected.getMessage().startsWith("IPC_BUSY:"))throw expected;
            }
            System.out.println("Saturation returned a handled command error");
        } finally {for(Wire.Connection c:watches)c.close();}
        var pool=Executors.newFixedThreadPool(24);
        try {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<24;i++)futures.add(pool.submit(()->{
                start.await();
                for(int j=0;j<3;j++) {
                    JSONObject value=Wire.call(new JSONObject().put("command","status"));
                    if(!value.getBoolean("service"))throw new AssertionError("Service unavailable");
                }
                return null;
            }));
            start.countDown();
            for(Future<?> future:futures)future.get(120,TimeUnit.SECONDS);
            System.out.println("72 calls from 24 concurrent workers completed");
        } finally {pool.shutdownNow();}
        System.out.println("IPC stress passed");
        System.exit(0);
    }
}
