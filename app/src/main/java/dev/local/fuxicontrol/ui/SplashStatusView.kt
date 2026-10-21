package dev.local.fuxicontrol.ui

import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import org.json.JSONObject

@Composable
internal fun SplashStatusView(data: JSONObject, apps: List<JSONObject>, icons: AppIcons, writable: Boolean, retry: (String) -> Unit, open: (String) -> Unit) {
    SectionTitle("开屏广告")
    Text("应用更新后自动检查开屏入口。连续启动异常时仅暂停对应应用，可重新尝试。")
    val rows = data.getJSONArray("apps")
    for (index in 0 until rows.length()) {
        val row = rows.getJSONObject(index)
        val name = row.getString("package")
        val app = apps.firstOrNull { it.getString("package") == name }
        val report = row.optJSONObject("report")
        val state = when {
            !row.getBoolean("installed") -> "未安装"
            !row.getBoolean("matched") -> "应用签名不匹配"
            !row.getBoolean("requested") -> "未开启"
            row.optBoolean("suspended") -> "疑似兼容异常，已暂停"
            row.getString("mode") != "enforce" -> "当前模式不拦截"
            !row.getBoolean("connected") -> "等待应用连接，请重新打开应用"
            report?.optString("state") == "unsupported" -> "入口不匹配，未拦截"
            report?.optString("state") == "blocked" -> "已命中开屏入口"
            report?.optString("state") == "ready" -> if (row.optBoolean("crossVersion")) "跨版本入口匹配，等待开屏调用" else "入口已加载，等待开屏调用"
            else -> "等待适配器状态"
        }
        Entry(
            app?.getString("label") ?: name,
            "$state · ${row.optString("versionName", "—")}" +
                (if (row.optBoolean("suspended")) "\n${row.optString("suspendReason")}" else report?.let { "\n${it.optString("detail")}" } ?: ""),
            enabled = app != null,
            leading = app?.let { { ApplicationIcon(it, icons) } },
        ) { open(name) }
        if (row.optBoolean("suspended")) {
            OutlinedButton(onClick = { retry(name) }, enabled = writable) { Text("重新尝试") }
            Text("重试后重新打开应用；原有屏蔽开关保持不变。")
        }
    }
}
