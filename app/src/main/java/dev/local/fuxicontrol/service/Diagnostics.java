package dev.local.fuxicontrol.service;

public final class Diagnostics {
    public static void main(String[] names) throws Exception {
        for(String name:names) {
            Class<?> type=Class.forName(name,false,Diagnostics.class.getClassLoader());
            for(var m:type.getDeclaredMethods()) {
                if(m.getName().matches("(?i).*(startProcessLocked|isReadyToBeExecutedLocked|enqueueUidChange|acquireWakeLockInternal|removeTask|broadcastIntentLocked|startServiceLocked|setServiceForegroundInnerLocked|setResumedActivityUncheckLocked|setResumedActivityUncheckedLocked|updateTopResumedActivityIfNeeded|getSourceUid|getSourcePackageName|getType|getBaseIntent|freeze|getClientUid|isActive|shouldSkipMessage|handleProcessStartedLocked|removeProcessLocked|noteOperation|startOperation|getUid|getActualUid|addAppLocked|self|enqueueNotificationInternal|get.*Focus|performReceiveLocked).*"))
                    System.out.println(m.toGenericString());
            }
            System.out.println("super="+type.getSuperclass());
            for(var f:type.getFields())
                if(f.getName().matches("info|appInfo|intent|name|foregroundServiceType|mUid|uid|packageName|owningUid|callingUid|mService|mAm")) System.out.println(f.toGenericString());
        }
    }
}
