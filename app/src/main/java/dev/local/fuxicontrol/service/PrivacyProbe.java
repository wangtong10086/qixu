package dev.local.fuxicontrol.service;

/** Exercise permission and AppOps ownership using only our disposable APK. */
public final class PrivacyProbe {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        String name="dev.local.fuxicontrol.probe",camera="android.permission.CAMERA";
        var context=AndroidContext.system();
        var directory=java.nio.file.Path.of("/data/adb/fuxicontrol/probe-privacy");
        java.nio.file.Files.createDirectories(directory);
        Shell.run("pm","grant",name,camera);
        var policy=new org.json.JSONObject().put("permissions",new org.json.JSONObject().put(camera,"deny"))
                .put("appOps",new org.json.JSONObject().put("android:camera","ignore"));
        var effective=new org.json.JSONObject().put(name,policy);
        var controller=new PrivacyController(new Catalog(context),directory);
        controller.validate(effective);
        try {
            controller.apply(effective,true);
            if(context.getPackageManager().checkPermission(camera,name)!=android.content.pm.PackageManager.PERMISSION_DENIED)
                throw new IllegalStateException("Permission was not revoked");
            if(!Shell.run("cmd","appops","get",name,"android:camera").contains("ignore"))
                throw new IllegalStateException("AppOps was not restricted");
            System.out.println("permissionAndAppOpsRestricted=true");
        } finally {controller.apply(effective,false);}
        if(context.getPackageManager().checkPermission(camera,name)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Permission was not restored");
        System.out.println("permissionRestored=true");
        System.out.println("restoredAppOps="+Shell.run("cmd","appops","get",name,"android:camera"));
        System.out.println("journal="+Text.read(directory.resolve("privacy.json")));
    }
}
