package dev.local.fuxicontrol.core;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class ResourceJournalTest {
    @Test public void sameBootPreservesRestorationData() {
        JSONObject entries=new JSONObject().put("task:10",new JSONObject().put("beforeNice",0));
        JSONObject saved=new JSONObject().put("bootId","a").put("entries",entries);
        assertEquals(entries.toString(),ResourceJournal.entries(saved,"a").toString());
        assertEquals(0,ResourceJournal.entries(saved,"b").length());
    }
    @Test public void existingFormatIsPreservedOnUpgrade() {
        JSONObject saved=new JSONObject().put("task:10",new JSONObject().put("beforeNice",0));
        assertEquals(saved.toString(),ResourceJournal.entries(saved,"a").toString());
    }
}
