package dev.local.fuxicontrol.service;

public final class NativeProbe {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        Catalog catalog=new Catalog(AndroidContext.system());
        var info=catalog.context().getPackageManager().getApplicationInfo("dev.local.fuxicontrol.probe",0);
        System.out.println(Native.networkApply(info.uid,Long.parseLong(args[0]),Long.parseLong(args[1]),65536,0));
        System.out.println(Native.networkStats(info.uid));
    }
}
