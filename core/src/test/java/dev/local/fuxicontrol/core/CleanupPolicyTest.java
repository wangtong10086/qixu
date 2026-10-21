package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CleanupPolicyTest {
    @Test public void oldConfigurationsKeepCleanupDisabled() {
        var config=Policy.initial();config.getJSONObject("defaults").remove("screenOffCleanup");
        config.getJSONObject("defaults").remove("screenOffDelaySeconds");
        var effective=Policy.effective(config,"com.example.app",false);
        assertEquals("off",effective.getString("screenOffCleanup"));
        assertEquals(60,effective.getInt("screenOffDelaySeconds"));
    }
    @Test public void forceStopAndComponentsCannotBecomeGlobalDefaults() {
        var config=Policy.initial();
        config.getJSONObject("defaults").put("screenOffCleanup","force-stop");
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
        config.getJSONObject("defaults").put("screenOffCleanup","cached");
        config.getJSONObject("apps").put("com.example.app",new JSONObject().put("screenOffCleanup","force-stop"));
        Policy.validate(config);
        config.getJSONObject("defaults").put("services",new JSONObject().put("com.example.Worker","block"));
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
    }
    @Test public void activeWorkAndProtectionPreventCleanup() {
        assertEquals("",CleanupDecision.skipReason(false,true,false,false,false,false,false));
        assertEquals("screen-on",CleanupDecision.skipReason(true,true,false,false,false,false,false));
        assertEquals("not-enforcing",CleanupDecision.skipReason(false,false,false,false,false,false,false));
        assertEquals("protected-uid",CleanupDecision.skipReason(false,true,true,false,false,false,false));
        assertEquals("resident",CleanupDecision.skipReason(false,true,false,true,false,false,false));
        assertEquals("foreground",CleanupDecision.skipReason(false,true,false,false,true,false,false));
        assertEquals("foreground-service",CleanupDecision.skipReason(false,true,false,false,false,true,false));
        assertEquals("audio",CleanupDecision.skipReason(false,true,false,false,false,false,true));
    }
    @Test public void invalidDelaysAndComponentNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->Policy.validatePatch(new JSONObject().put("screenOffDelaySeconds",0)));
        assertThrows(IllegalArgumentException.class,()->Policy.validatePatch(new JSONObject().put("services",new JSONObject().put("pkg/Bad;name","block"))));
    }
}
