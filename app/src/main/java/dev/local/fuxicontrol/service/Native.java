package dev.local.fuxicontrol.service;

public final class Native {
    static { System.load("/data/adb/fuxicontrol/libfuxicontrol.so"); }
    public static native boolean terminateProcess(int pid,int uid,long startTicks) throws java.io.IOException;
    public static native String networkApply(int uid,long uploadBps,long downloadBps,int burstBytes,int denyMask);
    public static native String networkStats(int uid);
    public static native void networkClear();
    public static native void networkPrune(long[] groups);
    public static native long cgroupId(String path) throws java.io.IOException;
    public static native void processNetworkApply(long group,long upload,long download,int burst,int deny);
    public static native void networkAlias(int uid,int owner);
    public static native int[] getAffinity(int tid) throws android.system.ErrnoException;
    public static native void setAffinity(int tid,int[] cpus) throws android.system.ErrnoException;
    public static native void clamp(int tid,int minimum,int maximum);
    public static native int[] getClamp(int tid);
}
