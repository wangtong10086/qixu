package dev.local.fuxicontrol.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsViewsTest {
    @Test
    fun settingsChildrenReturnToTheirActualParent() {
        listOf("系统能力", "最近事件", "网络统计", "CLI", "配置管理", "开源许可").forEach {
            assertEquals("设置", parentDestination(it, "设置", listOf("设置")))
        }
        assertEquals("配置管理", parentDestination("默认模板", "设置", listOf("设置", "配置管理")))
        assertEquals("应用例外", parentDestination("应用策略", "设置", listOf("设置", "配置管理", "应用例外")))
        assertEquals("概览", parentDestination("系统能力", "概览", listOf("概览")))
        assertEquals("概览", parentDestination("设置", "设置", emptyList()))
    }

    @Test
    fun ruleFormConvertsDelayAndKeepsOtherFields() {
        val rules =
            normalizedRules(
                """[{"id":"example","enabled":false,"package":"com.example.app","event":"foreground-left","action":"kill-cached","delayMs":"30000","note":"retained"}]"""
            )
        val rule = rules.getJSONObject(0)
        assertTrue(rule.get("delayMs") is Number)
        assertEquals(30000L, rule.getLong("delayMs"))
        assertEquals("retained", rule.getString("note"))
        assertFalse(rule.getBoolean("enabled"))
    }

    @Test
    fun invalidDelaysAreRejectedBeforeSaving() {
        listOf("", "1.5", "-1", "86400001", "abc").forEach {
            val text =
                """[{"id":"example","enabled":false,"package":"com.example.app","event":"foreground-left","action":"record","delayMs":"$it"}]"""
            assertThrows(IllegalArgumentException::class.java) { normalizedRules(text) }
        }
    }

    @Test
    fun trafficBytesHaveExplicitUnits() {
        assertEquals("0 B", formatBytes(0))
        assertTrue(formatBytes(1024).endsWith(" KiB"))
        assertTrue(formatBytes(1048576).endsWith(" MiB"))
        assertTrue(formatBytes(1073741824).endsWith(" GiB"))
    }
}
