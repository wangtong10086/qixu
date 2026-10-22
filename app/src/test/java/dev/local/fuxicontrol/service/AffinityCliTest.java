package dev.local.fuxicontrol.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class AffinityCliTest {
    @Test public void typedCommandsAndGlobalFlags() throws Exception {
        var r=Cli.request(new String[]{"cpu","background-little","true","--expected-revision","8","--dry-run"});
        assertEquals("scheduling.patch",r.getString("command"));assertTrue(r.getJSONObject("patch").getBoolean("backgroundSmallCoresOnly"));assertTrue(r.getBoolean("dryRun"));assertEquals(8,r.getLong("expectedRevision"));
        assertEquals("threads.list",Cli.request(new String[]{"thread","list","test.example","test.example:worker"}).getString("command"));
        assertEquals("profile.activate",Cli.request(new String[]{"profile","activate","default"}).getString("command"));
    }
    @Test public void flagsDoNotBreakRateParsing() throws Exception {
        var r=Cli.request(new String[]{"limit","set","test.example","--upload","1mbps","--dry-run","--expected-revision","5"});
        assertEquals(1000000,r.getJSONObject("patch").getLong("uploadBps"));assertEquals(5,r.getLong("expectedRevision"));
    }
    @Test public void invalidReplaceAndCeilingAreRejected() {
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"app","inherit","test.example","--replace"}));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"cpu","background-little","yes"}));
    }
}
