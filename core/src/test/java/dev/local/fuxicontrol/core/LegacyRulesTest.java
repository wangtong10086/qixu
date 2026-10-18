package dev.local.fuxicontrol.core;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRulesTest {
    private JSONArray source() throws Exception {
        try(var input=getClass().getResourceAsStream("/legacy-rules.json")) {
            return new JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void importsVerifiedRulesWithTheirDelaysAndNonstopReclaim() throws Exception {
        JSONArray rules=LegacyRules.convert(source());
        assertEquals(3,rules.length());
        assertEquals("record",rules.getJSONObject(0).getString("action"));
        assertEquals(0,rules.getJSONObject(0).getLong("delayMs"));
        assertEquals(30000,rules.getJSONObject(1).getLong("delayMs"));
        assertEquals("foreground-left",rules.getJSONObject(2).getString("event"));
        assertEquals("kill-cached",rules.getJSONObject(2).getString("action"));
    }
    @Test(expected=IllegalArgumentException.class) public void refusesChangedActionsUnderTheSameName() throws Exception {
        JSONArray records=source();
        JSONArray definitions=new JSONArray(records.getJSONObject(1).getString("rawJson"));
        definitions.getJSONObject(0).put("actions",new JSONArray().put("su.exe(\"am force-stop com.tencent.mm\");"));
        records.getJSONObject(1).put("rawJson",definitions.toString());
        LegacyRules.convert(records);
    }
}
