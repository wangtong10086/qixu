package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.core.Profiles
import org.json.JSONObject

@Composable
internal fun ProfileView(config: JSONObject, enabled: Boolean, submit: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    SectionTitle("配置方案")
    Text("当前：${Profiles.name(config)}", style = MaterialTheme.typography.titleMedium)
    Text("修改会保存到当前方案。切换会替换默认模板、应用例外、自动化、推送和运行模式。", style = MaterialTheme.typography.bodyMedium)
    val profiles = config.optJSONObject("profiles") ?: JSONObject()
    profiles.keys().asSequence().forEach { id ->
        val entry = profiles.getJSONObject(id)
        Entry(
            entry.getString("name"),
            if (id == config.optString("activeProfile")) "使用中" else "切换到此方案",
            enabled = enabled && id != config.optString("activeProfile"),
        ) {
            selected = id
        }
    }
    OutlinedTextField(
        name,
        { name = it },
        Modifier.fillMaxWidth(),
        label = { Text("新方案名称") },
        singleLine = true,
        enabled = enabled,
    )
    Button(
        enabled = enabled && name.isNotBlank() && name.trim().length <= 40,
        onClick = {
            submit(JSONObject().put("command", "profile.create").put("name", name.trim()))
            name = ""
        },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("复制当前配置并使用")
    }
    selected?.let { id ->
        val entry = profiles.getJSONObject(id)
        val target = entry.getJSONObject("config")
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("切换到${entry.getString("name")}") },
            text = {
                Text(
                    "将应用此方案的默认模板、${target.getJSONObject("apps").length()} 个应用例外、${target.getJSONArray("rules").length()} 条自动化规则和推送设置。运行模式：${modeLabel(target.getString("mode"))}。当前配置会保留。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        submit(JSONObject().put("command", "profile.activate").put("id", id))
                        selected = null
                    },
                ) {
                    Text("切换")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } },
        )
    }
}
