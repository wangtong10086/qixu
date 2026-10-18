package dev.local.fuxicontrol.service;

public final class StandbyProbe {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        var catalog=new Catalog(AndroidContext.system());
        String name="dev.local.fuxicontrol.probe";
        int uid=catalog.context().getPackageManager().getApplicationInfo(name,0).uid;
        var directory=java.nio.file.Path.of("/data/adb/fuxicontrol/probe-standby");java.nio.file.Files.createDirectories(directory);
        var controller=new StandbyController(catalog,directory);
        var effective=new org.json.JSONObject().put(name,new org.json.JSONObject().put("uid",uid).put("background","restricted"));
        String before=Shell.run("am","get-standby-bucket",name).trim();
        try {
            controller.apply(effective,true,java.util.Set.of());
            int applied=Integer.parseInt(Shell.run("am","get-standby-bucket",name).trim());
            if(applied<40) throw new IllegalStateException("Rare standby bucket was not applied");
            System.out.println("standbyBucket="+applied);
            System.out.println("effectiveIdle="+Shell.run("am","get-inactive",name).trim());
        } finally {controller.apply(effective,false,java.util.Set.of());}
        if(!Shell.run("am","get-standby-bucket",name).trim().equals(before)) throw new IllegalStateException("Bucket was not restored");
        System.out.println("bucketRestored=true");
    }
}
