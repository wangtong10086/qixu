package dev.local.fuxicontrol.service;

/** Applies only to our disposable validation APK and restores settings before returning. */
public final class ResourceProbe {
    public static void main(String[] args) throws Exception {
        if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
        if(args.length>0 && args[0].equals("inspect")) {
            int pid=Integer.parseInt(args[1]);int[] clamp=Native.getClamp(pid);
            System.out.println(new org.json.JSONObject().put("pid",pid).put("nice",android.os.Process.getThreadPriority(pid))
                    .put("uclampMin",clamp[0]).put("uclampMax",clamp[1]));return;
        }
        var context=AndroidContext.system();
        System.out.println("audioActivityQueries="+new AudioActivity(context).activeUids().size());
        int uid=context.getPackageManager().getApplicationInfo("dev.local.fuxicontrol.probe",0).uid;
        var root=java.nio.file.Path.of("/data/adb/fuxicontrol/probe-resources");java.nio.file.Files.createDirectories(root);
        var resources=new ResourceController(root);
        int pid=Integer.parseInt(Shell.run("pidof","dev.local.fuxicontrol.probe").trim());
        int beforeNice=android.os.Process.getThreadPriority(pid);int[] beforeClamp=Native.getClamp(pid);
        var resident=new ResidentController();
        var effective=new org.json.JSONObject().put("dev.local.fuxicontrol.probe",new org.json.JSONObject().put("uid",uid).put("resident",true));
        try {
            resident.apply(effective,true);System.out.println("residentApplied=true");
            resources.scanProcesses();resources.scheduling(uid,0,512,true);
            if(android.os.Process.getThreadPriority(pid)!=beforeNice) throw new IllegalStateException("Clamp-only policy changed CPU nice");
            resources.restoreAll();System.out.println("clampOnlyPreservesPriority=true");
            resources.scheduling(uid,9,512,true);resources.memory(uid,64,0);
            System.out.println("memory.high="+Text.read(java.nio.file.Path.of("/sys/fs/cgroup/apps/uid_"+uid+"/memory.high")).trim());
            if(android.os.Process.getThreadPriority(pid)!=Math.max(beforeNice,9) || Native.getClamp(pid)[1]!=512)
                throw new IllegalStateException("Kernel scheduling state does not match requested values");
            System.out.println("kernelSchedulingVerified=true");
            System.out.println("journal="+Text.read(root.resolve("resources.json")));
            Native.clamp(pid,128,512);android.os.Process.setThreadPriority(pid,19);
            resources.restoreAll();
            if(Native.getClamp(pid)[0]!=128 || android.os.Process.getThreadPriority(pid)!=19)
                throw new IllegalStateException("Restore overwrote another actor's scheduler update");
            System.out.println("externalSchedulerChangesPreserved=true");
        } finally {
            resources.restoreAll();resident.apply(effective,false);
            // Release only the synthetic scheduler update introduced by this fixture.
            Native.clamp(pid,beforeClamp[0],beforeClamp[1]);android.os.Process.setThreadPriority(pid,beforeNice);
        }
        if(android.os.Process.getThreadPriority(pid)!=beforeNice || !java.util.Arrays.equals(Native.getClamp(pid),beforeClamp))
            throw new IllegalStateException("Kernel scheduling state was not restored");
        System.out.println("kernelRestoreVerified=true");
        System.out.println("restored="+Text.read(root.resolve("resources.json")));
    }
}
