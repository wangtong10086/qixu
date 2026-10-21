package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SplashHealthTest {
    private static final String APP="com.example.splash", KEY="1:100:2", BOOT="boot-a";
    private SplashHealth health=new SplashHealth(new JSONObject());
    private void start(int pid,long at) { health.arm(APP,KEY,pid,BOOT,at); }
    private void crash(int pid,long at) { health.exit(APP,KEY,pid,BOOT,at,SplashHealth.Exit.CRASH); }
    private boolean suspended() { return health.status(APP,KEY).getBoolean("suspended"); }

    @Test public void twoStartupFailuresSuspendOnlyThisInstallAndSurviveRestart() {
        start(10,1000);crash(10,2000);assertFalse(suspended());
        health=new SplashHealth(new JSONObject(health.saved().toString()));
        start(11,3000);health.exit(APP,KEY,11,BOOT,4000,SplashHealth.Exit.ANR);
        assertTrue(suspended());
        assertFalse(health.status("com.example.other",KEY).getBoolean("suspended"));
        assertFalse(health.status(APP,"2:200:2").getBoolean("suspended"));
    }
    @Test public void retriesPreserveOtherAppsAndClearTheFailedSession() {
        start(10,1000);crash(10,2000);start(11,3000);crash(11,4000);
        health.arm("com.example.other",KEY,20,BOOT,1000);
        health.retry(APP,KEY);
        assertFalse(suspended());assertNull(health.session(APP,KEY));
        assertNotNull(health.session("com.example.other",KEY));
        start(12,5000);crash(12,6000);assertFalse(suspended());
    }
    @Test public void forceStopsAndLateCrashesAreNotStartupFailures() {
        start(10,1000);health.exit(APP,KEY,10,BOOT,2000,SplashHealth.Exit.OTHER);
        start(11,3000);crash(11,64000);
        start(12,65000);crash(12,66000);assertFalse(suspended());
    }
    @Test public void healthyStartupResetsConsecutiveFailures() {
        start(10,1000);crash(10,2000);start(11,3000);
        health.healthy(APP,KEY,11,BOOT,63000);
        start(12,64000);crash(12,65000);assertFalse(suspended());
    }
    @Test public void earlyHeartbeatCannotEraseFailures() {
        start(10,1000);crash(10,2000);start(11,3000);
        health.healthy(APP,KEY,11,BOOT,4000);crash(11,5000);assertTrue(suspended());
    }
    @Test public void ignoresOtherProcessesBootsAndOldExitRecords() {
        start(10,1000);crash(99,2000);crash(10,999);
        health.exit(APP,KEY,10,"boot-b",2000,SplashHealth.Exit.CRASH);
        crash(10,2000);assertFalse(suspended());
    }
    @Test public void duplicateExitIsCountedOnce() {
        start(10,1000);crash(10,2000);crash(10,2000);assertFalse(suspended());
    }
    @Test public void failuresExpireAfterFiveMinutes() {
        start(10,1000);crash(10,2000);start(11,303000);crash(11,304000);assertFalse(suspended());
    }
    @Test public void passiveOrUnsupportedAdapterDoesNotArmBreaker() {
        health.begin(APP,KEY,10,BOOT,1000);crash(10,2000);
        start(11,3000);crash(11,4000);assertFalse(suspended());
    }
    @Test public void disabledPolicyStopsAttributingCrashesToAdapter() {
        start(10,1000);health.disarm(APP,KEY,10);crash(10,2000);
        start(11,3000);crash(11,4000);assertFalse(suspended());
    }
    @Test public void reconnectCannotResetTheStartupClock() {
        start(10,1000);health.begin(APP,KEY,10,BOOT,59000);
        assertEquals(1000,health.session(APP,KEY).getLong("started"));
        health.healthy(APP,KEY,10,BOOT,61000);
        assertTrue(health.session(APP,KEY).getBoolean("settled"));
    }
}
