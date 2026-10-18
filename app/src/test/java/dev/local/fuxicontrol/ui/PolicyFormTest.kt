package dev.local.fuxicontrol.ui

import org.junit.Assert.*
import org.junit.Test

class PolicyFormTest {
    @Test
    fun numericInputUsesIntegerWireValues() {
        val result = normalizedPolicy("""{"uploadBps":"512000","memoryHighMiB":"256"}""")
        assertTrue(result.get("uploadBps") is Number)
        assertEquals(512000, result.getLong("uploadBps"))
        assertEquals(256, result.getLong("memoryHighMiB"))
        assertFalse(result.has("cpuNice"))
    }

    @Test
    fun wakeLockEditorSupportsMultipleLines() {
        val result = normalizedPolicy("""{"wakeLocks":"tag-a\n\n tag-b \n"}""")
        assertEquals("[\"tag-a\",\"tag-b\"]", result.getJSONArray("wakeLocks").toString())
    }

    @Test
    fun invalidLimitsCannotReachService() {
        listOf("-1", "abc", "1.5", "1000000000001", "").forEach {
            assertNotNull(policyError("""{"uploadBps":"$it"}"""))
        }
        assertNotNull(policyError("""{"uclampMax":"0"}"""))
        assertNull(policyError("""{"uploadBps":"0"}"""))
    }
}
