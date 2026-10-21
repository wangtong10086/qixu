package dev.local.fuxicontrol.service;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.json.JSONObject;

public final class RootDaemon {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        Path root=Path.of(args[0]);
        var lockChannel=java.nio.channels.FileChannel.open(root.resolve("service.lock"),
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
        var lock=lockChannel.tryLock();
        if(lock==null) throw new IllegalStateException("Fuxi Control service is already running");
        Catalog catalog=new Catalog(AndroidContext.system());
        Store store=new Store(root);
        Audit audit=new Audit(root);
        RuntimeEngine engine=new RuntimeEngine(store,catalog,audit);
        SplashChannel splash=new SplashChannel(store,catalog.context());
        splash.start();
        Commands commands=new Commands(store,catalog,audit,engine,splash);
        String signer=Text.read(root.resolve("manager.sha256")).trim();
        engine.reconcile();
        var pool=Executors.newCachedThreadPool();
        Semaphore slots=new Semaphore(16);
        LocalServerSocket listener;
        Native.socketLabel(true);
        try {listener=new LocalServerSocket(Wire.ADDRESS);}
        finally {Native.socketLabel(false);}
        try(LocalServerSocket server=listener) {
            Store.atomic(root.resolve("service.ready"),Integer.toString(android.system.Os.getpid()));
            audit.record("service-start",new JSONObject().put("pid",android.system.Os.getpid()));
            while(true) {
                LocalSocket socket=server.accept();
                int uid=socket.getPeerCredentials().getUid();
                if(uid!=0 && uid!=1000 && !catalog.managerUid(uid,signer)) {socket.close();continue;}
                if(!slots.tryAcquire()) {socket.close();continue;}
                pool.execute(()-> {
                    try(socket) {
                        socket.setSoTimeout(10000);
                        BufferedReader reader=Wire.reader(socket);
                        StringBuilder line=new StringBuilder();int ch;
                        while((ch=reader.read())!=-1 && ch!='\n') {
                            if(line.length()>=1024*1024) throw new IllegalArgumentException("Request exceeds 1 MiB");
                            line.append((char)ch);
                        }
                        JSONObject request=new JSONObject(line.toString());
                        String command=request.getString("command");
                        if(uid==1000 && !Set.of("watch","event","status").contains(command))
                            throw new SecurityException("System peer is restricted to hook events and snapshots");
                        if(command.equals("watch")) {
                            long revision=-1;
                            while(true) {
                                JSONObject config=store.after(revision);
                                Wire.send(socket,catalog.snapshot(config));revision=config.getLong("revision");
                            }
                        } else {
                            try { Wire.send(socket,new JSONObject().put("ok",true).put("result",commands.execute(request))); }
                            catch(Exception e) { Wire.send(socket,new JSONObject().put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage())); }
                        }
                    } catch(Exception e) { android.util.Log.e("FuxiControl","Request failed",e); }
                    finally {slots.release();}
                });
            }
        }
    }
}
