package dev.local.fuxicontrol.core;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProcessPolicyTest {
    @Test public void migrationIncludesInactiveProfilesAndPreservesRevision() {
        JSONObject old=Policy.initial().put("schema",1).put("revision",42);
        Profiles.create(old,"另一方案");
        JSONObject next=Policy.migrate(old);
        assertEquals(3,next.getInt("schema"));assertEquals(42,next.getInt("revision"));
        for(String id:Json.keys(next.getJSONObject("profiles"))) assertEquals(3,next.getJSONObject("profiles").getJSONObject(id).getJSONObject("config").getInt("schema"));
        assertEquals(1,old.getInt("schema"));
    }
    @Test public void separateStagesKeepUidBudgetsShared() {
        JSONObject app=Policy.effective(Policy.initial(),"test.example",false).put("uploadBps",1000000).put("memoryMaxMiB",512);
        JSONObject rule=new JSONObject("{foreground:{cpuNice:2,uploadBps:200000},background:{cpuNice:12,memoryMaxMiB:64}}");
        ProcessPolicy.validate(new JSONObject().put("test.example:worker",rule));
        JSONObject fg=ProcessPolicy.effective(app,rule,true,false),bg=ProcessPolicy.effective(app,rule,false,false);
        assertEquals(2,fg.getInt("cpuNice"));assertEquals(12,bg.getInt("cpuNice"));
        assertEquals(0,fg.getInt("memoryMaxMiB"));assertEquals(0,bg.getLong("uploadBps"));
    }
    @Test public void activeTaskExemptionCanBeExplicitlyDisabled() {
        JSONObject app=Policy.effective(Policy.initial(),"test.example",false);
        JSONObject rule=new JSONObject("{background:{cpuNice:12,memoryMaxMiB:64,uploadBps:10000}}");
        JSONObject protectedValue=ProcessPolicy.effective(app,rule,false,true);
        assertEquals(0,protectedValue.getInt("cpuNice"));assertEquals(10000,protectedValue.getLong("uploadBps"));
        rule.put("exemptActiveTasks",false);
        assertEquals(12,ProcessPolicy.effective(app,rule,false,true).getInt("cpuNice"));
    }
    @Test public void rejectNonResourceFields() {
        assertThrows(IllegalArgumentException.class,()->ProcessPolicy.validate(new JSONObject("{'test.example':{background:{autostart:'deny'}}}")));
        assertThrows(IllegalArgumentException.class,()->ProcessPolicy.validate(new JSONObject("{'test.example':{background:{memoryHighMiB:100,memoryMaxMiB:10}}}")));
    }
    @Test public void statNamesMayContainSpacesAndParentheses() {
        String[] values=new String[22];java.util.Arrays.fill(values,"0");values[0]="S";values[11]="20";values[12]="30";values[19]="777";values[21]="12";
        ResourceSample sample=ResourceSample.parse("15 (a name ) odd) "+String.join(" ",values),4096);
        assertEquals(50,sample.ticks());assertEquals(777,sample.start());assertEquals(49152,sample.rssBytes());
        assertEquals(5.0,ResourceSample.cpu(50,0,1000,0),0.001);
        assertTrue(Double.isNaN(ResourceSample.cpu(1,50,1000,0)));
        assertEquals(12.5,ResourceSample.processCpu(200,0,2000,100,8),0.001);
        assertEquals(6.25,ResourceSample.processCpu(200,0,4000,100,8),0.001);
    }
}
