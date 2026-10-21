package dev.local.fuxicontrol.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundCliTest {
    @Test public void delayUsesNumericPolicyAndStopCanBePreviewed() throws Exception {
        var delay=Cli.request(new String[]{"app","set","com.example.app","screenOffDelaySeconds","60"});
        assertEquals(60,delay.getJSONObject("patch").getLong("screenOffDelaySeconds"));
        var stop=Cli.request(new String[]{"service","stop","com.example.app","com.example.Worker","--dry-run"});
        assertEquals("service.stop",stop.getString("command"));assertTrue(stop.getBoolean("dryRun"));
    }
    @Test public void restoreReleasesOnlyTheNamedComponent() throws Exception {
        var restore=Cli.request(new String[]{"service","restore","com.example.app","com.example.Worker"});
        assertEquals("service.policy",restore.getString("command"));assertEquals("system",restore.getString("mode"));
        assertEquals("com.example.Worker",restore.getString("class"));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"service","disable-all","com.example.app","com.example.Worker"}));
    }
}
