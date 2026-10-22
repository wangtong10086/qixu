package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResourceUpgradeTest {
    @Test public void aggregateBudgetAndPhases() {
        JSONObject quota=CpuQuota.defaults().put("foregroundUs",200000).put("backgroundUs",50000);
        CpuQuota.validate(quota);
        assertEquals(.5,CpuQuota.cores(quota,false),0);
        assertEquals(2,CpuQuota.cores(quota,true),0);
        assertEquals(0,CpuQuota.budget(quota,false,true));
    }
    @Test public void protectedAppQuotaCanBeExplicitWithoutRemovingOtherProtection() {
        JSONObject config=Policy.initial();
        JSONObject quota=CpuQuota.defaults().put("backgroundUs",1000).put("allowProtected",true);
        config.getJSONObject("apps").put("com.example.mail",new JSONObject().put("cpuQuota",quota));
        CpuQuota.validate(quota);
        JSONObject effective=Policy.effective(config,"com.example.mail",true);
        assertTrue(effective.getBoolean("protected"));
        assertEquals("allow",effective.getString("autostart"));
        assertEquals("allow",effective.getString("network"));
        assertEquals(.01,CpuQuota.cores(effective.getJSONObject("cpuQuota"),false),0);
        assertEquals(0,CpuQuota.cores(quota,true),0);
        assertThrows(IllegalArgumentException.class,()->CpuQuota.validateSharedUid(quota,CpuQuota.defaults().put("backgroundUs",1000)));
    }
    @Test public void rejectsFractionalAndInvalidPeriod() {
        assertThrows(IllegalArgumentException.class,()->CpuQuota.validate(CpuQuota.defaults().put("periodUs",999)));
        assertThrows(IllegalArgumentException.class,()->CpuQuota.validate(CpuQuota.defaults().put("backgroundUs",1500.5)));
        assertThrows(IllegalArgumentException.class,()->CpuQuota.validateSharedUid(CpuQuota.defaults(),CpuQuota.defaults().put("backgroundUs",10000)));
    }
    @Test public void cleanupRequiresAllConditionsAndFreshMeasurements() {
        JSONObject rule=ManualCleanup.defaults().put("cpuPercent",10).put("rssMiB",64);
        assertEquals("",ManualCleanup.skip(rule,60000,12.0,100L*1048576,true));
        assertFalse(ManualCleanup.skip(rule,59000,12.0,100L*1048576,true).isEmpty());
        assertFalse(ManualCleanup.skip(rule,60000,null,100L*1048576,true).isEmpty());
        assertFalse(ManualCleanup.skip(rule,60000,12.0,100L*1048576,false).isEmpty());
        assertFalse(ManualCleanup.skip(rule,60000,12.0,63L*1048576,true).isEmpty());
    }
    @Test public void windowRejectsGapsAndWeightsIntervals() {
        CpuWindow window=new CpuWindow();
        for(int ms=0;ms<=30000;ms+=2000) window.add(ms,20.0);
        assertEquals(20,window.average(30000),0);
        assertNull(window.average(36000));
        window.add(40000,90.0);
        assertNull(window.average(40000));
    }
    @Test public void migrationAddsSafeDefaultsToInactiveProfiles() {
        JSONObject old=Policy.initial().put("schema",2).put("revision",7);
        old.getJSONObject("defaults").remove("cpuQuota");old.getJSONObject("defaults").remove("manualCleanup");
        JSONObject inactive=Policy.copy(old);
        old.put("activeProfile","saved");
        old.put("profiles",new JSONObject().put("saved",new JSONObject().put("name","Saved").put("config",inactive)));
        JSONObject next=Policy.migrate(old);
        assertEquals(7,next.getLong("revision"));
        assertEquals(0,next.getJSONObject("defaults").getJSONObject("cpuQuota").getLong("backgroundUs"));
        assertEquals("cached",next.getJSONObject("profiles").getJSONObject("saved").getJSONObject("config").getJSONObject("defaults").getJSONObject("manualCleanup").getString("mode"));
        assertFalse(old.getJSONObject("defaults").has("cpuQuota"));
    }
    @Test public void globalForceStopIsRejected() {
        JSONObject config=Policy.initial();config.getJSONObject("defaults").put("manualCleanup",ManualCleanup.defaults().put("mode","force-stop"));
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
    }
}
