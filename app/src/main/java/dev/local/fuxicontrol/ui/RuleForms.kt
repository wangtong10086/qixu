package dev.local.fuxicontrol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.core.Rules
import org.json.JSONArray
import org.json.JSONObject

internal fun normalizedRules(text: String): JSONArray {
    val rules = JSONArray(text)
    for (i in 0 until rules.length()) {
        val rule = rules.getJSONObject(i)
        val delay =
            rule.get("delayMs").toString().toLongOrNull()
                ?: throw IllegalArgumentException("延迟时间请输入整数毫秒")
        require(delay in 0..86400000) { "延迟时间须在 0–86400000 毫秒之间" }
        rule.put("delayMs", delay)
        Rules.validate(rule)
    }
    return rules
}

@Composable
private fun ApplicationPicker(
    apps: List<JSONObject>,
    icons: AppIcons,
    close: () -> Unit,
    choose: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索名称或包名") },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(
                        apps.filter {
                            it.getString("label").contains(query, true) ||
                                it.getString("package").contains(query, true)
                        },
                        key = { it.getString("package") },
                    ) { app ->
                        ListItem(
                            headlineContent = { Text(app.getString("label")) },
                            supportingContent = { Text(app.getString("package")) },
                            leadingContent = { ApplicationIcon(app, icons) },
                            modifier = Modifier.clickable { choose(app.getString("package")) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("取消") } },
    )
}

@Composable
internal fun RuleForms(
    draft: String,
    push: Boolean,
    apps: List<JSONObject>,
    icons: AppIcons,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Int?>(null) }
    val rules = if (push) JSONArray() else JSONArray(draft)
    val proxies = if (push) JSONObject(draft) else JSONObject()
    val names =
        if (push) proxies.keys().asSequence().toList()
        else (0 until rules.length()).map { rules.getJSONObject(it).getString("package") }
    names.forEachIndexed { index, name ->
        val app = apps.firstOrNull { it.getString("package") == name }
        ListItem(
            headlineContent = { Text(app?.getString("label") ?: name) },
            supportingContent = { Text(name) },
            leadingContent = app?.let { item -> { ApplicationIcon(item, icons) } },
            modifier = Modifier.clickable(enabled = enabled && !push) { editingRule = index },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        val value = if (push) proxies.getJSONObject(name) else rules.getJSONObject(index)
        fun set(field: String, next: Any) {
            value.put(field, next)
            change(if (push) proxies.toString() else rules.toString())
        }
        val switches =
            if (push)
                listOf(
                    "enabled" to "启用推送代理",
                    "showContent" to "显示消息内容",
                    "startOnPush" to "收到推送时启动",
                    "skipIfRunning" to "应用运行时跳过",
                    "sound" to "通知声音",
                    "vibrate" to "通知振动",
                )
            else listOf("enabled" to "启用规则")
        switches.forEach { (field, title) ->
            ListItem(
                headlineContent = { Text(title) },
                trailingContent = {
                    Switch(value.optBoolean(field), { set(field, it) }, enabled = enabled)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (!push) {
            OutlinedTextField(
                value.getString("id"),
                { set("id", it) },
                Modifier.fillMaxWidth(),
                label = { Text("规则名称") },
                enabled = enabled,
                singleLine = true,
            )
            SectionTitle("触发条件")
            Choice(
                value.getString("event"),
                listOf("foreground-left" to "离开前台", "push-arrived" to "收到推送"),
                enabled,
            ) {
                set("event", it)
            }
            SectionTitle("执行动作")
            Choice(
                value.getString("action"),
                listOf("record" to "记录事件", "kill-cached" to "清理缓存进程"),
                enabled,
            ) {
                set("action", it)
            }
            val delay = value.get("delayMs").toString()
            OutlinedTextField(
                delay,
                { set("delayMs", it) },
                Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("延迟（毫秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = delay.toLongOrNull()?.let { it !in 0..86400000 } ?: true,
                supportingText = { Text("0–86400000 毫秒；仅用于清理缓存进程") },
                singleLine = true,
            )
        }
        TextButton(
            enabled = enabled,
            onClick = {
                if (push) {
                    proxies.remove(name)
                    change(proxies.toString())
                } else {
                    rules.remove(index)
                    change(rules.toString())
                }
            },
        ) {
            Text(if (push) "移除配置" else "删除规则")
        }
        HorizontalDivider()
    }
    if (names.isEmpty())
        Text(if (push) "尚未配置推送代理" else "尚未添加自动化规则", Modifier.padding(vertical = 24.dp))
    Button(enabled = enabled, onClick = { selecting = true }) { Text(if (push) "添加应用" else "添加规则") }
    if (editingRule != null)
        ApplicationPicker(apps, icons, { editingRule = null }) { name ->
            rules.getJSONObject(editingRule!!).put("package", name)
            change(rules.toString())
            editingRule = null
        }
    if (selecting)
        ApplicationPicker(
            if (push) apps.filter { !proxies.has(it.getString("package")) } else apps,
            icons,
            { selecting = false },
        ) { name ->
            if (push) {
                proxies.put(name, JSONObject().put("enabled", false))
                change(proxies.toString())
            } else {
                rules.put(
                    JSONObject()
                        .put("id", java.util.UUID.randomUUID().toString())
                        .put("package", name)
                        .put("enabled", false)
                        .put("event", "foreground-left")
                        .put("action", "record")
                        .put("delayMs", 0)
                )
                change(rules.toString())
            }
            selecting = false
        }
}
