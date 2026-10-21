package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun ServiceListView(data: JSONObject, enabled: Boolean, reload: () -> Unit, action: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Text("停用组件会阻止后续启动与绑定，可能影响通知、同步或下载；不会直接杀死当前进程。恢复后还原栖序修改前的组件状态。")
    OutlinedButton(onClick = reload) { Text("重新加载") }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索服务名称或进程") }, singleLine = true)
    val services = data.getJSONArray("services")
    val rows = (0 until services.length()).map { services.getJSONObject(it) }
        .filter { it.getString("class").contains(query, true) || it.optString("process").contains(query, true) }
    Text("${rows.size} 个服务 · ${rows.count { it.getBoolean("running") }} 个运行中", style = MaterialTheme.typography.labelLarge)
    for (service in rows) {
        val name = service.getString("class")
        val blocked = service.getBoolean("blocked")
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name.substringAfterLast('.'), style = MaterialTheme.typography.titleMedium)
            Text(name, style = MaterialTheme.typography.bodySmall)
            Text(when {
                !service.getBoolean("declared") -> "应用更新后已不存在 · 可移除旧规则"
                service.getBoolean("foreground") -> "前台服务 · PID ${service.getInt("pid")}"
                service.getBoolean("running") -> "运行中 · PID ${service.getInt("pid")} · ${service.getInt("clients")} 个客户端"
                else -> "未运行"
            })
            Text(if (blocked && data.getBoolean("protected")) "规则已暂停：关键服务保护" else if (blocked) "策略：阻止启动与绑定" else if (!service.optBoolean("enabled", true)) "组件已被系统或其他工具停用" else "策略：跟随系统")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { action(name, if (blocked) "system" else "block") },
                    enabled = enabled && (!data.getBoolean("protected") || blocked)) {
                    Text(if (blocked) "恢复" else "停用组件")
                }
                if (service.getBoolean("running"))
                    OutlinedButton(onClick = { action(name, "stop") }, enabled = enabled && !data.getBoolean("protected") && !service.getBoolean("foreground")) {
                        Text("停止本次服务")
                    }
            }
        }
        HorizontalDivider()
    }
}
