package dev.local.fuxicontrol.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CleanupConfirmationTest {
    @Test fun anotherRunningJobCannotAuthorizeADifferentTarget() {
        val row=JSONObject().put("state","confirmation-required").put("package","com.example.a")
        val result=JSONObject().put("results",JSONArray().put(row))
        assertNull(confirmationFor(result,JSONObject().put("command","cleanup.quick")))
        assertNull(confirmationFor(result,JSONObject().put("command","cleanup.app").put("package","com.example.b")))
        assertNotNull(confirmationFor(result,JSONObject().put("command","cleanup.app").put("package","com.example.a")))
        row.put("pid",12).put("startTicks",100)
        val request=JSONObject().put("command","cleanup.process").put("package","com.example.a").put("pid",12).put("startTicks",101)
        assertNull(confirmationFor(result,request))
        assertNotNull(confirmationFor(result,request.put("startTicks",100)))
    }
}
