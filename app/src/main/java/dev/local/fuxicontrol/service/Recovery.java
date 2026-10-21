package dev.local.fuxicontrol.service;

/** Offline recovery, invoked only after stopping this controller's daemon. */
public final class Recovery {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        var directory=java.nio.file.Path.of("/data/adb/fuxicontrol");
        Store store=new Store(directory);
        var policy=store.get();policy.put("mode","disabled");store.replace(policy,false);
        Native.networkClear();new NetworkController().restore();
        new ResourceController(directory).restoreAll();
        Catalog catalog=new Catalog(AndroidContext.system());
        new StandbyController(catalog,directory).apply(new org.json.JSONObject(),false,java.util.Set.of());
        new PrivacyController(catalog,directory).apply(new org.json.JSONObject(),false);
        new ServiceController(catalog,directory).apply(new org.json.JSONObject(),false);
        System.out.println("Controller disabled; owned network, memory, scheduling, privacy and service component settings restored.");
    }
}
