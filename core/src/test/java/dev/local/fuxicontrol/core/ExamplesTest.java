package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Published examples must remain accepted by the same policy validator as the daemon. */
public class ExamplesTest {
    private JSONObject example(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/" + name)) {
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test public void affinityExamplesAreAccepted() throws Exception {
        Policy.validatePatch(example("app-affinity.json"));
        AffinityPolicy.threads(new org.json.JSONArray().put(example("thread-affinity.json")));
    }
    @Test public void initialExampleIsNonExecutingAndValid() throws Exception {
        JSONObject policy = example("policy.observe.json");
        Policy.validate(policy);
        assertEquals("observe", policy.getString("mode"));
        assertEquals(0, policy.getJSONObject("apps").length());
        assertEquals(0, policy.getJSONObject("defaults").getJSONObject("permissions").length());
    }
    @Test public void limitRequestIsVersionedAndDryRun() throws Exception {
        JSONObject request = example("app-limit.request.json");
        Policy.packageName(request.getString("package"));
        Policy.validatePatch(request.getJSONObject("patch"));
        assertEquals("app.replace", request.getString("command"));
        assertTrue(request.getBoolean("dryRun"));
        assertTrue(request.has("expectedRevision"));
    }
}
