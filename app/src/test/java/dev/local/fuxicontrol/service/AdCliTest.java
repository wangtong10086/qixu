package dev.local.fuxicontrol.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdCliTest {
    @Test public void adSwitchUsesTheSameTypedPolicyAsTheUi() throws Exception {
        var enabled=Cli.request(new String[]{"ads","enable","com.zhihu.android"});
        assertEquals("app.patch",enabled.getString("command"));
        assertEquals(Boolean.TRUE,enabled.getJSONObject("patch").get("blockSplashAds"));
        var disabled=Cli.request(new String[]{"app","set","com.zhihu.android","blockSplashAds","false"});
        assertEquals(Boolean.FALSE,disabled.getJSONObject("patch").get("blockSplashAds"));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"ads","reset","com.zhihu.android"}));
    }
    @Test public void retryIsSeparateFromThePolicySwitch() throws Exception {
        var request=Cli.request(new String[]{"ads","retry","com.taobao.taobao","--dry-run"});
        assertEquals("ads.retry",request.getString("command"));
        assertEquals("com.taobao.taobao",request.getString("package"));
        assertTrue(request.getBoolean("dryRun"));
        assertFalse(request.has("patch"));
    }
    @Test public void previewDoesNotEnableInjection() throws Exception {
        var request=Cli.request(new String[]{"ads","enable","com.zhihu.android","--dry-run"});
        assertTrue(request.getBoolean("dryRun"));
    }
}
