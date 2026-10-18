package dev.local.fuxicontrol.core;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class ProfilesTest {
    @Test public void switchesAndRetainsIndependentEdits() {
        JSONObject config=Policy.initial();
        Profiles.create(config,"出行");
        String travel=config.getString("activeProfile");
        config.getJSONObject("defaults").put("downloadBps",1000000);
        Profiles.sync(config);
        config=Profiles.activate(config,"default");
        assertEquals(0,config.getJSONObject("defaults").getLong("downloadBps"));
        config=Profiles.activate(config,travel);
        assertEquals(1000000,config.getJSONObject("defaults").getLong("downloadBps"));
        Policy.validate(config);
        assertFalse(config.getJSONObject("profiles").getJSONObject(travel).getJSONObject("config").has("profiles"));
    }
    @Test public void duplicateNamesRejected() {
        JSONObject config=Policy.initial();
        Profiles.create(config,"出行");
        assertThrows(IllegalArgumentException.class,()->Profiles.create(config," 出行 "));
    }
    @Test public void invalidInactivePolicyRejected() {
        JSONObject config=Policy.initial();Profiles.sync(config);
        config.getJSONObject("profiles").getJSONObject("default").getJSONObject("config").getJSONObject("defaults").put("uploadBps",-1);
        assertThrows(IllegalArgumentException.class,()->Policy.validate(config));
    }
}
