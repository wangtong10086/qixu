package dev.local.fuxicontrol.core;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;
import static org.junit.Assert.*;

public class DiscoveryTest {
    private JSONObject app() {
        return new JSONObject().put("label","Example camera").put("package","com.example.app")
                .put("permissions",new JSONArray()
                    .put(new JSONObject().put("name","android.permission.CAMERA").put("label","Camera").put("granted",false))
                    .put(new JSONObject().put("name","android.permission.ACCESS_FINE_LOCATION").put("label","Location").put("granted",true)));
    }
    private JSONObject policy() {return Policy.effective(Policy.initial(),"com.example.app",false);}
    @Test public void permissionQueriesDistinguishDeclaredAndGranted() {
        assertTrue(AppSearch.matches(app(),policy(),"相机","declared"));
        assertFalse(AppSearch.matches(app(),policy(),"相机","granted"));
        assertTrue(AppSearch.matches(app(),policy(),"定位","granted"));
        assertFalse(AppSearch.matches(app(),policy(),"定位","name"));
    }
    @Test public void termsAreCaseInsensitiveAndCombinedAcrossNameAndPermissions() {
        assertTrue(AppSearch.matches(app(),policy(),"EXAMPLE 定位","all"));
        assertFalse(AppSearch.matches(app(),policy(),"EXAMPLE 麦克风","all"));
        assertTrue(AppSearch.matches(app(),policy(),"  ","all"));
        assertFalse(AppSearch.matches(app(),policy(),".*","all"));
    }
    @Test public void policyRestrictionsDoNotImplyPermissionGrants() {
        JSONObject p=policy();
        p.getJSONObject("permissions").put("android.permission.CAMERA","system");
        assertFalse(AppSearch.matches(app(),p,"CAMERA","granted"));
        assertTrue(AppSearch.matches(app(),p,"禁止自启","policy"));
        assertFalse(AppSearch.matches(app(),p,"限速","policy"));
        p.put("downloadBps",1000);
        assertTrue(AppSearch.matches(app(),p,"限速","policy"));
    }
    @Test(expected=IllegalArgumentException.class) public void invalidSearchScopeRejected() {
        AppSearch.matches(app(),policy(),"camera","unknown");
    }
    private JSONObject process(int pid,int importance,String state,String... packages) {
        return new JSONObject().put("pid",pid).put("uid",10001).put("name","test:"+pid)
                .put("importance",importance).put("state",state).put("foregroundService",importance==125)
                .put("packages",new JSONArray(packages));
    }
    @Test public void processMembershipDoesNotInferSharedUidSiblingIsRunning() {
        JSONObject installed=new JSONObject().put("one",10001).put("sibling",10001);
        JSONObject grouped=ProcessGroups.group(new JSONArray().put(process(1,400,"cached","one","one","unknown")),installed,Set.of(10001));
        assertEquals(1,grouped.length());
        assertEquals(1,grouped.getJSONObject("one").getJSONArray("processes").length());
        assertEquals("cached",grouped.getJSONObject("one").getString("state"));
        assertTrue(grouped.getJSONObject("one").getBoolean("activeTask"));
    }
    @Test public void mostImportantProcessDeterminesGroupAndFgsIsRetained() {
        JSONObject grouped=ProcessGroups.group(new JSONArray().put(process(1,400,"cached","one"))
                .put(process(2,100,"foreground","one")).put(process(3,125,"background","one")),
                new JSONObject().put("one",10001),Set.of());
        JSONObject app=grouped.getJSONObject("one");
        assertEquals("foreground",app.getString("state"));
        assertEquals(3,app.getJSONArray("processes").length());
        assertTrue(app.getBoolean("foregroundService"));
        assertFalse(app.getBoolean("activeTask"));
        assertEquals(0,ProcessGroups.group(new JSONArray(),new JSONObject().put("one",10001),Set.of()).length());
    }
}
