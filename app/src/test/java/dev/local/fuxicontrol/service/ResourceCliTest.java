package dev.local.fuxicontrol.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class ResourceCliTest {
    @Test public void cleanupRunIsBoundToTheReviewedRevision() throws Exception {
        var request=Cli.request(new String[]{"cleanup","run","preview-id","27"});
        assertEquals("cleanup.run",request.getString("command"));
        assertEquals("preview-id",request.getString("id"));assertEquals(27,request.getLong("expectedRevision"));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"cleanup","all"}));
    }
    @Test public void cleanupCommandsPreserveProcessIdentityAndExplicitOverride() throws Exception {
        var quick=Cli.request(new String[]{"cleanup","quick","--dry-run"});
        assertEquals("cleanup.quick",quick.getString("command"));assertTrue(quick.getBoolean("dryRun"));
        var app=Cli.request(new String[]{"cleanup","app","com.example.app"});
        assertEquals("cleanup.app",app.getString("command"));assertFalse(app.optBoolean("overrideProtection"));
        var process=Cli.request(new String[]{"cleanup","process","com.example.app","1234","87654321","--override-protection"});
        assertEquals("cleanup.process",process.getString("command"));
        assertEquals(1234,process.getInt("pid"));assertEquals(87654321L,process.getLong("startTicks"));
        assertTrue(process.getBoolean("overrideProtection"));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"cleanup","process","com.example.app","1234"}));
        assertThrows(IllegalArgumentException.class,()->Cli.request(new String[]{"cleanup","quick","com.example.app"}));
    }
    @Test public void structuredBudgetsRemainObjects() throws Exception {
        var request=Cli.request(new String[]{"app","set","com.example.app","cpuQuota","{periodUs:100000,foregroundUs:0,backgroundUs:50000}"});
        assertEquals(50000,request.getJSONObject("patch").getJSONObject("cpuQuota").getLong("backgroundUs"));
        var cleanup=Cli.request(new String[]{"app","set","com.example.app","manualCleanup","{mode:cached,backgroundSeconds:60,cpuPercent:0,rssMiB:0}"});
        assertEquals("cached",cleanup.getJSONObject("patch").getJSONObject("manualCleanup").getString("mode"));
    }
}
