package dev.local.fuxicontrol.core;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class AffinityPolicyTest {
    @Test public void persistentAndForegroundServicesAreStillBackground() {
        assertFalse(AffinityPolicy.isForeground(0));assertTrue(AffinityPolicy.isForeground(1));assertTrue(AffinityPolicy.isForeground(2));assertFalse(AffinityPolicy.isForeground(4));assertFalse(AffinityPolicy.isForeground(20));
    }
    @Test public void onlyThreadsBornAfterBindingInheritTheOwnedBaseline() {
        JSONObject entries=new JSONObject("{'41':{pid:40,processStart:'100',boundAtTicks:200,applied:'0,1',before:'0,1,2,3',systemBefore:true}}");
        assertNull(AffinityPolicy.inheritedBaseline(entries,40,"100",150,"0,1"));
        assertNotNull(AffinityPolicy.inheritedBaseline(entries,40,"100",201,"0,1"));
        assertNull(AffinityPolicy.inheritedBaseline(entries,40,"101",201,"0,1"));
        assertNull(AffinityPolicy.inheritedBaseline(entries,40,"100",201,"2,3"));
    }
    @Test public void inactiveRulesDoNotRequireThreadScanning() {
        JSONObject app=Policy.effective(Policy.initial(),"test.example",false);
        assertFalse(AffinityPolicy.requiresBinding(app,"foreground",false));
        assertTrue(AffinityPolicy.requiresBinding(app,"background",true));
        app.put("processes",new JSONObject("{'test.example':{background:{cpuAffinity:little}}}"));
        assertFalse(AffinityPolicy.requiresBinding(app,"foreground",false));
        assertTrue(AffinityPolicy.requiresBinding(app,"background",false));
    }
    @Test public void rangesAreCanonicalAndBounded() {
        assertEquals("0,1,2,4",AffinityPolicy.format(AffinityPolicy.parse("0-2,1,4")));
        for(String value:List.of("","3-1","-1","0,,2","4096","0-999999"))assertThrows(IllegalArgumentException.class,()->AffinityPolicy.parse(value));
    }
    @Test public void phaseInheritanceAndOrderedThreads() {
        JSONObject config=Policy.initial();config.getJSONObject("defaults").put("cpuAffinity",new JSONObject("{foreground:big,background:little}"));
        config.getJSONObject("apps").put("test.example",new JSONObject("{cpuAffinity:{foreground:system}}"));
        JSONObject app=Policy.effective(config,"test.example",true);
        assertEquals("little",app.getJSONObject("cpuAffinity").getString("background"));
        JSONObject process=new JSONObject("{foreground:{cpuAffinity:prime},threads:[{id:first,match:{type:glob,name:'Render*'},foreground:{cpuAffinity:big}},{id:second,match:{type:exact,name:RenderThread},background:{cpuAffinity:little}}]}");
        ProcessPolicy.validate(new JSONObject().put("test.example",process));
        assertEquals("big",AffinityPolicy.resolve(app,process,"foreground",false,"RenderThread").getString("selection"));
        assertEquals("thread:second",AffinityPolicy.resolve(app,process,"background",false,"RenderThread").getString("source"));
        assertEquals("prime",AffinityPolicy.resolve(app,process,"foreground",true,"main").getString("selection"));
    }
    @Test public void patternsAreNotRegexAndMainIgnoresName() {
        assertTrue(AffinityPolicy.matches(new JSONObject("{type:main}"),true,"renamed"));
        assertFalse(AffinityPolicy.matches(new JSONObject("{type:main}"),false,"main"));
        JSONObject pattern=new JSONObject("{type:glob,name:'pool.[?]*'}");
        assertTrue(AffinityPolicy.matches(pattern,false,"pool.[1]worker"));
        assertFalse(AffinityPolicy.matches(pattern,false,"poolX1worker"));
    }
    @Test public void threadRulesRejectInvalidOrDuplicateIds() {
        JSONObject rule=new JSONObject("{id:r,match:{type:main},foreground:{cpuAffinity:system}}");
        assertThrows(IllegalArgumentException.class,()->AffinityPolicy.threads(new JSONArray().put(rule).put(rule)));
        assertThrows(IllegalArgumentException.class,()->AffinityPolicy.threads(new JSONArray().put(new JSONObject("{id:r,match:{type:main},background:{cpuNice:5}}"))));
    }
    @Test public void mergePreservesOtherProcessesButReplacesOrderedRules() {
        JSONObject before=new JSONObject("{permissions:{'android.permission.CAMERA':deny},processes:{'test.example':{foreground:{cpuNice:4},threads:[{id:old}]},'test.example:worker':{background:{cpuNice:10}}}}");
        JSONObject after=AffinityPolicy.merge(before,new JSONObject("{processes:{'test.example':{foreground:{cpuAffinity:big},threads:[]}}}"));
        assertEquals(4,after.getJSONObject("processes").getJSONObject("test.example").getJSONObject("foreground").getInt("cpuNice"));
        assertTrue(after.getJSONObject("processes").has("test.example:worker"));
        assertEquals(0,after.getJSONObject("processes").getJSONObject("test.example").getJSONArray("threads").length());
        assertEquals(1,before.getJSONObject("processes").getJSONObject("test.example").getJSONArray("threads").length());
        assertThrows(IllegalArgumentException.class,()->AffinityPolicy.merge(before,new JSONObject("{permissions:null}")));
    }
    @Test public void migrationPreservesAllProfilesAndLeavesCeilingOff() {
        JSONObject old=Policy.initial().put("schema",3);old.remove("scheduling");old.getJSONObject("defaults").remove("cpuAffinity");Profiles.create(old,"alternative");
        JSONObject next=Policy.migrate(old);assertFalse(next.getJSONObject("scheduling").getBoolean("backgroundSmallCoresOnly"));
        for(String id:Json.keys(next.getJSONObject("profiles"))){JSONObject profile=next.getJSONObject("profiles").getJSONObject(id).getJSONObject("config");assertEquals(4,profile.getInt("schema"));assertFalse(profile.getJSONObject("scheduling").getBoolean("backgroundSmallCoresOnly"));}
    }
}
