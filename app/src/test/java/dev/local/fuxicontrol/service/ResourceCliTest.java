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
    @Test public void structuredBudgetsRemainObjects() throws Exception {
        var request=Cli.request(new String[]{"app","set","com.example.app","cpuQuota","{periodUs:100000,foregroundUs:0,backgroundUs:50000}"});
        assertEquals(50000,request.getJSONObject("patch").getJSONObject("cpuQuota").getLong("backgroundUs"));
        var cleanup=Cli.request(new String[]{"app","set","com.example.app","manualCleanup","{mode:cached,backgroundSeconds:60,cpuPercent:0,rssMiB:0}"});
        assertEquals("cached",cleanup.getJSONObject("patch").getJSONObject("manualCleanup").getString("mode"));
    }
}
