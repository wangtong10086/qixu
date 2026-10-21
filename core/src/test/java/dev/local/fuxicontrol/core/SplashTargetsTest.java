package dev.local.fuxicontrol.core;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class SplashTargetsTest {
    @Test public void acceptsVersionUpdatesButRejectsDifferentSigner() {
        var target=SplashTargets.find("com.tencent.qqmusic");
        assertTrue(target.matches(target.version(),target.certificate()));
        assertTrue(target.matches(target.version()+1,target.certificate()));
        assertTrue(target.matches(target.version()-1,target.certificate()));
        assertFalse(target.matches(target.version(),"other"));
    }
    @Test public void cannotEnableGloballyOrForAnUnadaptedApp() {
        var config=Policy.initial();
        config.getJSONObject("defaults").put("blockSplashAds",true);
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
        config.getJSONObject("defaults").remove("blockSplashAds");
        config.getJSONObject("apps").put("com.example.unknown",new JSONObject().put("blockSplashAds",true));
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
    }
    @Test public void profileSwitchKeepsAdChoicesSeparate() {
        var config=Policy.initial();Profiles.sync(config);
        Profiles.create(config,"广告测试");
        config.getJSONObject("apps").put("com.zhihu.android",new JSONObject().put("blockSplashAds",true));
        Profiles.sync(config);
        config=Profiles.activate(config,"default");
        assertFalse(config.getJSONObject("apps").has("com.zhihu.android"));
        Policy.validate(config);
    }
}
