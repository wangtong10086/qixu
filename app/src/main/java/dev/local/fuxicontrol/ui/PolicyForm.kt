package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.core.Policy
import org.json.JSONArray
import org.json.JSONObject

internal data class Field(
    val key: String,
    val title: String,
    val group: String,
    val hint: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val kind: String = "number",
)

internal val policyFields =
    listOf(
        Field(
            "autostart",
            "允许自启动",
            "启动与后台",
            choices = listOf("allow" to "允许", "deny" to "禁止"),
            kind = "choice",
        ),
        Field(
            "background",
            "后台策略",
            "启动与后台",
            choices = listOf("normal" to "正常", "balanced" to "平衡", "restricted" to "严格"),
            kind = "choice",
        ),
        Field("blockSplashAds", "屏蔽开屏广告", "启动与后台", "仅作用于已适配版本；广告状态页可查看是否加载。", kind = "bool"),
        Field("resident", "保持常驻", "启动与后台", kind = "bool"),
        Field("cleanupOnTaskRemoved", "划走任务后清理", "启动与后台", kind = "bool"),
        Field("screenOffCleanup", "息屏清理", "启动与后台", "亮屏取消等待；音乐、前台服务、常驻和关键服务受保护。强停会中断后台任务并可能延迟消息。",
            choices = listOf("off" to "关闭", "cached" to "回收缓存", "force-stop" to "强制停止"), kind = "choice"),
        Field("screenOffDelaySeconds", "息屏后等待", "启动与后台", "秒 · 默认 60，范围 10–86400；保存策略后重新计时"),
        Field("protected", "关键服务保护", "启动与后台", "保护角色提供基础豁免，逐项例外仍优先。", kind = "bool"),
        Field("cpuNice", "后台 CPU 优先级", "CPU 与内存", "0–19，数值越大优先级越低"),
        Field("uclampMax", "CPU 调度上限", "CPU 与内存", "1–1024，1024 表示不限制；不是 CPU 百分比"),
        Field("memoryHighMiB", "内存回收阈值", "CPU 与内存", "MiB · 0 表示不限制"),
        Field("memoryMaxMiB", "内存硬上限", "CPU 与内存", "MiB · 0 表示不限制；超限可能终止进程"),
        Field(
            "network",
            "网络访问",
            "网络",
            choices = listOf("allow" to "允许", "background-deny" to "禁止后台", "deny" to "禁止全部"),
            kind = "choice",
        ),
        Field("uploadBps", "上传速率上限", "网络", "bit/s · 0 关闭限速", kind = "rate"),
        Field("downloadBps", "下载速率上限", "网络", "bit/s · 0 关闭限速", kind = "rate"),
        Field("burstBytes", "突发窗口", "网络", "Bytes · 65536–16777216；超限丢包"),
        Field(
            "permissions",
            "运行时权限",
            "权限与唤醒",
            "完整权限名，例如 android.permission.READ_CONTACTS",
            listOf("deny" to "拒绝", "system" to "跟随系统"),
            "map",
        ),
        Field(
            "appOps",
            "AppOps",
            "权限与唤醒",
            "操作名称，由服务验证系统支持情况",
            listOf(
                "allow" to "允许",
                "ignore" to "忽略",
                "deny" to "拒绝",
                "foreground" to "仅前台",
                "default" to "系统默认",
            ),
            "map",
        ),
        Field("wakeLocks", "禁止的唤醒锁标签", "权限与唤醒", "每行一个完整标签；留空不拦截", kind = "list"),
    )

internal fun policyValueLabel(field: Field, value: Any?): String =
    when {
        value == null -> "继承"
        field.kind == "choice" -> field.choices.first { it.first == value.toString() }.second
        field.kind == "bool" -> if (value == true) "开启" else "关闭"
        else -> value.toString()
    }

internal fun normalizedPolicy(draft: String): JSONObject {
    val result = JSONObject(draft)
    policyFields
        .filter { result.has(it.key) }
        .forEach { field ->
            if (field.kind in listOf("number", "rate")) {
                val number =
                    result.get(field.key).toString().toLongOrNull()
                        ?: throw IllegalArgumentException("${field.title}需要填写整数")
                result.put(field.key, number)
            }
            if (field.kind == "list" && result.get(field.key) is String)
                result.put(
                    field.key,
                    JSONArray(
                        result
                            .getString(field.key)
                            .lines()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                    ),
                )
        }
    Policy.validatePatch(result)
    return result
}

internal fun policyError(draft: String): String? =
    try {
        normalizedPolicy(draft)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    } catch (e: org.json.JSONException) {
        e.message
    }

@Composable
internal fun PolicyForm(
    draft: String,
    effective: JSONObject,
    template: Boolean,
    enabled: Boolean,
    packageName: String? = null,
    change: (String) -> Unit,
) {
    val value = JSONObject(draft)
    fun put(key: String, item: Any) {
        val next = JSONObject(draft)
        next.put(key, item)
        change(next.toString())
    }
    var group = ""
    policyFields
        .filter { !template || it.key !in listOf("protected", "blockSplashAds") }
        .filter { it.key != "blockSplashAds" || dev.local.fuxicontrol.core.SplashTargets.find(packageName ?: "") != null }
        .forEach { field ->
            if (group != field.group) {
                group = field.group
                SectionTitle(group)
                if (group == "CPU 与内存")
                    Text(
                        "CPU 时间配额：当前内核不支持。优先级和调度上限只限制后台调度，不是时间硬配额。",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            val overridden = value.has(field.key)
            val current = if (overridden) value.get(field.key) else effective.opt(field.key)
                ?: if (field.key == "screenOffDelaySeconds") 60 else null
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        field.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!template) {
                        if (overridden)
                            TextButton(
                                enabled = enabled,
                                onClick = {
                                    val next = JSONObject(draft)
                                    next.remove(field.key)
                                    change(next.toString())
                                },
                            ) {
                                Text("恢复继承")
                            }
                        else
                            Text(
                                if (
                                    effective.optJSONObject("sources")?.optString(field.key) ==
                                        "protected-role"
                                )
                                    "角色保护"
                                else "继承默认",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                    }
                }
                when (field.kind) {
                    "bool" ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (current == true) "已开启" else "已关闭",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(current == true, { put(field.key, it) }, enabled = enabled)
                        }
                    "choice" ->
                        Choice(
                            current?.toString() ?: field.choices.first().first,
                            field.choices.filter { !template || field.key != "screenOffCleanup" || it.first != "force-stop" },
                            enabled,
                        ) {
                            put(field.key, it)
                        }
                    "rate" -> {
                        val text = current?.toString() ?: "0"
                        Switch(
                            text != "0",
                            { put(field.key, if (it) 1_000_000L else 0L) },
                            enabled = enabled,
                        )
                        if (text != "0") {
                            Choice(
                                text,
                                listOf(
                                    "256000" to "256 kbit/s",
                                    "1000000" to "1 Mbit/s",
                                    "5000000" to "5 Mbit/s",
                                    "10000000" to "10 Mbit/s",
                                ),
                                enabled,
                            ) {
                                put(field.key, it)
                            }
                            OutlinedTextField(
                                text,
                                { put(field.key, it) },
                                Modifier.fillMaxWidth(),
                                enabled = enabled,
                                label = { Text("${field.title} · bit/s") },
                                singleLine = true,
                                isError = text.toLongOrNull() == null,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        } else Text("不限速")
                        Text(
                            "超速丢包，允许突发窗口；共享 UID 的应用共用上限。下载限制作用于到达设备后的流量。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    "number" -> {
                        val text = current?.toString() ?: "0"
                        OutlinedTextField(
                            text,
                            { put(field.key, it) },
                            Modifier.fillMaxWidth(),
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(field.title) },
                            supportingText = { Text(field.hint) },
                            isError = text.toLongOrNull() == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    "list" -> {
                        val text =
                            if (current is JSONArray)
                                (0 until current.length()).joinToString("\n") {
                                    current.getString(it)
                                }
                            else current?.toString() ?: ""
                        OutlinedTextField(
                            text,
                            { put(field.key, it) },
                            Modifier.fillMaxWidth(),
                            enabled = enabled,
                            label = { Text(field.title) },
                            supportingText = { Text(field.hint) },
                        )
                    }
                    "map" ->
                        MapEditor(current as? JSONObject ?: JSONObject(), field, enabled) {
                            put(field.key, it)
                        }
                }
                if (field.hint.isNotEmpty() && field.kind in listOf("bool", "choice"))
                    Text(field.hint, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
        }
}

@Composable
internal fun Choice(
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    packageName: String? = null,
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(options.firstOrNull { it.first == value }?.second ?: value)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        change(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun MapEditor(
    map: JSONObject,
    field: Field,
    enabled: Boolean,
    change: (JSONObject) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(field.choices.first().first) }
    map.keys().asSequence().toList().forEach { key ->
        Text(key, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(map.getString(key), field.choices, enabled) {
                val n = JSONObject(map.toString())
                n.put(key, it)
                change(n)
            }
            TextButton(
                enabled = enabled,
                onClick = {
                    val n = JSONObject(map.toString())
                    n.remove(key)
                    change(n)
                },
            ) {
                Text("移除")
            }
        }
    }
    if (map.length() == 0) Text("未设置限制", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        name,
        { name = it },
        Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text("新增名称") },
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Ascii,
            ),
        supportingText = { Text(field.hint) },
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(selection, field.choices, enabled) { selection = it }
        TextButton(
            enabled = enabled && name.isNotBlank(),
            onClick = {
                val n = JSONObject(map.toString())
                n.put(name.trim(), selection)
                change(n)
                name = ""
            },
        ) {
            Text("添加")
        }
    }
}
