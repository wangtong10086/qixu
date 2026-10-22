package dev.local.fuxicontrol.service;



import java.nio.file.Files;
import java.nio.file.Path;
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
        android.os.IInterface activity=(android.os.IInterface)Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        activity.asBinder().linkToDeath(()-> {
            android.util.Log.e("QixuIPC","System process died; supervisor will rebuild framework connections");
            System.exit(1);
        },0);
        Store store=new Store(root);
        Audit audit=new Audit(root);
        RuntimeEngine engine=new RuntimeEngine(store,catalog,audit);
        SplashChannel splash=new SplashChannel(store,catalog.context());
        ResourceMonitor resources=new ResourceMonitor(root,engine,catalog);
        Commands commands=new Commands(store,catalog,audit,engine,splash,resources);
        String signer=Text.read(root.resolve("manager.sha256")).trim();
        engine.reconcile();
        ControllerEndpoint endpoint=new ControllerEndpoint(store,catalog,commands,splash,signer);
        while(true) {
            try(BrokerConnection broker=new BrokerConnection()) {
                android.os.Bundle registration=new android.os.Bundle();registration.putBinder("controller",endpoint);
                broker.call("register",registration);
                Store.atomic(root.resolve("service.ready"),Integer.toString(android.system.Os.getpid()));
                audit.record("service-start",new JSONObject().put("pid",android.system.Os.getpid()).put("transport","binder"));
                broker.awaitDeath();
            } catch(ReflectiveOperationException | java.io.IOException e) {
                Files.deleteIfExists(root.resolve("service.ready"));
                android.util.Log.e("QixuIPC","Broker unavailable; waiting for local process",e);
            }
            Thread.sleep(2000);
        }
    }
}
