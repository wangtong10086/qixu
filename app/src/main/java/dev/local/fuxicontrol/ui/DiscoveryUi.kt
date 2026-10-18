package dev.local.fuxicontrol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.R
import org.json.JSONObject

internal val processLabels =
    linkedMapOf("foreground" to "前台", "visible" to "可见", "background" to "后台", "cached" to "缓存")

@Composable
internal fun DiscoverySearch(
    query: String,
    onQuery: (String) -> Unit,
    scope: String,
    onScope: (String) -> Unit,
) {
    OutlinedTextField(
        query,
        onQuery,
        Modifier.fillMaxWidth(),
        label = { Text("搜索应用、包名或权限") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = {
            if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("清除") }
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
                "all" to "综合",
                "name" to "名称",
                "declared" to "声明权限",
                "granted" to "已授予权限",
                "policy" to "策略内容",
            )
            .forEach { (value, label) ->
                FilterChip(scope == value, { onScope(value) }, label = { Text(label) })
            }
    }
    Text(
        if (scope == "granted") "已授予权限不代表允许在后台使用。" else "多个关键词用空格分隔。",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun RunningSummary(snapshot: JSONObject?, failure: String, onSelect: (String) -> Unit) {
    SectionTitle("运行中的应用")
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (snapshot == null) Text(if (failure.isEmpty()) "正在读取进程状态…" else "暂时无法读取进程状态")
            else {
                val running = snapshot.getJSONObject("apps")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    processLabels.forEach { (state, label) ->
                        val count =
                            running.keys().asSequence().count {
                                running.getJSONObject(it).getString("state") == state
                            }
                        TextButton(
                            onClick = { onSelect(if (state == "cached") "已缓存" else label) },
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            Column {
                                Text("$count", style = MaterialTheme.typography.headlineSmall)
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (failure.isNotEmpty())
                Text(
                    "状态更新失败，以上为上次快照：$failure",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}

internal fun processDescription(process: JSONObject?, loaded: Boolean): String {
    if (!loaded) return "运行状态待读取"
    if (process == null) return "未发现运行进程"
    return buildString {
        append(processLabels.getValue(process.getString("state")))
        append(" · ${process.getJSONArray("processes").length()} 个进程")
        if (process.getBoolean("foregroundService")) append(" · 前台服务")
        if (process.getBoolean("activeTask")) append(" · 活动任务（UID）")
    }
}

internal fun permissionEvidence(app: JSONObject, query: String, scope: String): String {
    if (query.isBlank() || scope in listOf("name", "policy")) return ""
    val permissions = app.getJSONArray("permissions")
    val matches =
        (0 until permissions.length())
            .map { permissions.getJSONObject(it) }
            .filter {
                (scope != "granted" || it.getBoolean("granted")) &&
                    query.trim().split(Regex("\\s+")).any { term ->
                        dev.local.fuxicontrol.core.AppSearch.textMatches(
                            dev.local.fuxicontrol.core.AppSearch.permissionText(it),
                            term,
                        )
                    }
            }
    return matches.take(2).joinToString(
        separator = "",
        postfix = if (matches.size > 2) "\n另 ${matches.size - 2} 项匹配权限" else "",
    ) {
        "\n${it.getString("label")} · ${if (it.getBoolean("granted")) "已授予" else "已声明，未授予"}"
    }
}
