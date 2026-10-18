package dev.local.fuxicontrol.core;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class PolicyTest {
    @Test public void defaultChangeReachesExistingAndNewApps() {
        JSONObject c = Policy.initial();
        c.getJSONObject("apps").put("com.example.existing", new JSONObject());
        c.getJSONObject("defaults").put("cpuNice", 9);
        assertEquals(9, Policy.effective(c,"com.example.existing",false).getInt("cpuNice"));
        assertEquals(9, Policy.effective(c,"com.example.newapp",false).getInt("cpuNice"));
    }
    @Test public void rolesProtectDefaultImeAndExplicitNetworkCapSurvivesProtection() {
        JSONObject c = Policy.initial();
        c.getJSONObject("apps").put("com.example.keyboard",new JSONObject().put("downloadBps",2000000));
        JSONObject p = Policy.effective(c,"com.example.keyboard",true);
        assertEquals("allow",p.getString("autostart"));
        assertEquals(0,p.getInt("cpuNice"));
        assertEquals(2000000,p.getLong("downloadBps"));
        assertEquals("app",p.getJSONObject("sources").getString("downloadBps"));
    }
    @Test public void criticalRolesDoNotInheritPrivacyOrBandwidthRestrictions() {
        JSONObject c=Policy.initial();
        c.getJSONObject("defaults").put("permissions",new JSONObject().put("android.permission.READ_CONTACTS","deny"))
            .put("downloadBps",1000000);
        JSONObject p=Policy.effective(c,"com.example.keyboard",true);
        assertEquals(0,p.getJSONObject("permissions").length());
        assertEquals(0,p.getLong("downloadBps"));
    }
    @Test public void explicitResidentReceivesProtection() {
        JSONObject c=Policy.initial();
        c.getJSONObject("apps").put("com.example.resident",new JSONObject().put("protected",true));
        assertEquals("allow",Policy.effective(c,"com.example.resident",false).getString("autostart"));
    }
    @Test public void explicitOptOutOfRoleProtectionRestoresTheOrdinaryDefaults() {
        JSONObject c=Policy.initial();
        c.getJSONObject("apps").put("com.example.keyboard",new JSONObject().put("protected",false));
        JSONObject p=Policy.effective(c,"com.example.keyboard",true);
        assertEquals("deny",p.getString("autostart"));
        assertEquals(5,p.getInt("cpuNice"));
    }
    @Test public void pushProxyOptInPreservesExplicitPrivacyAndStartsDisabled() {
        JSONObject c=Policy.initial();
        assertFalse(Policy.pushProxy(c,"com.example.chat").getBoolean("enabled"));
        c.getJSONObject("pushProxy").put("com.example.chat",new JSONObject().put("enabled",true).put("showContent",true));
        Policy.validate(c);
        JSONObject p=Policy.pushProxy(c,"com.example.chat");
        assertTrue(p.getBoolean("enabled"));assertTrue(p.getBoolean("showContent"));
        assertFalse(p.getBoolean("sound"));
    }
    @Test public void privacyTemplateStartsEmpty() {
        JSONObject c = Policy.initial(); Policy.validate(c);
        assertEquals(0,c.getJSONObject("defaults").getJSONObject("permissions").length());
        assertEquals(0,c.getJSONObject("defaults").getJSONObject("appOps").length());
    }
    @Test(expected=IllegalArgumentException.class) public void invalidCpuClampIsRejected() {
        Policy.validatePatch(new JSONObject().put("uclampMax",0));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownFieldsAreRejected() {
        Policy.validatePatch(new JSONObject().put("memoryHghMiB",64));
    }
    @Test public void rulesNeverMatchAnotherPackage() {
        JSONObject r = new JSONObject().put("id","test").put("enabled",true).put("event","push-arrived")
            .put("package","com.tencent.mm").put("action","kill-cached").put("delayMs",30000);
        Rules.validate(r);
        assertTrue(Rules.matches(r,"push-arrived","com.tencent.mm"));
        assertFalse(Rules.matches(r,"push-arrived","com.example.other"));
    }
}
