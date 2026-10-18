package dev.local.fuxicontrol.service;

public final class Native {
    static { System.load("/data/adb/fuxicontrol/libfuxicontrol.so"); }
    public static native String networkApply(int uid,long uploadBps,long downloadBps,int burstBytes,int denyMask);
    public static native String networkStats(int uid);
    public static native void networkClear();
    public static native void networkAlias(int uid,int owner);
    public static native void clamp(int tid,int minimum,int maximum);
    public static native int[] getClamp(int tid);
    public static native void socketLabel(boolean enable);
}
