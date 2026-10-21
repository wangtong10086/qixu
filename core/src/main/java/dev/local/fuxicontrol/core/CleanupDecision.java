package dev.local.fuxicontrol.core;

/** Rechecked at execution time; role protection applies to every member of a shared UID. */
public final class CleanupDecision {
    public static String skipReason(boolean interactive, boolean enforce, boolean protectedUid,
            boolean resident, boolean foreground, boolean foregroundService, boolean playing) {
        if(interactive) return "screen-on";
        if(!enforce) return "not-enforcing";
        if(protectedUid) return "protected-uid";
        if(resident) return "resident";
        if(foreground) return "foreground";
        if(foregroundService) return "foreground-service";
        if(playing) return "audio";
        return "";
    }
    private CleanupDecision() {}
}
