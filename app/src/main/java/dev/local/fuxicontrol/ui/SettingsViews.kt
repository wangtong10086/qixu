package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

internal fun parentDestination(page: String, tab: String, parents: List<String>): String =
    parents.lastOrNull() ?: if (page != tab) tab else "概览"

internal val featureNames =
    mapOf(
        "startup" to "自启动拦截",
        "foreground" to "前后台识别",
        "wakelock" to "唤醒锁控制",
        "push" to "推送识别",
        "jobs" to "后台作业",
        "task-removed" to "划走清理",
        "provider" to "内容提供者唤醒",
        "push-broadcast" to "推送广播",
        "active-task" to "活动任务识别",
        "freezer" to "缓存冻结",
        "package-observer" to "安装与更新监听",
        "broadcast" to "广播拦截",
        "process-started" to "进程归属识别",
        "permissions-observer" to "权限变更监听",
        "push-proxy" to "推送代理",
        "native-notification" to "通知服务",
    )

@Composable
internal fun DataRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
}

@Composable
internal fun CapabilityView(report: JSONObject) {
    SectionTitle("系统环境")
    DataRow("Android API", report.getInt("androidSdk").toString())
    DataRow("所需 Vector API", report.getInt("requiredVectorApi").toString())
    SectionTitle("资源控制")
    listOf(
            "memoryHigh" to "内存回收阈值",
            "memoryMax" to "内存硬上限",
            "uclamp" to "CPU 调度上限",
            "cpuBandwidthQuota" to "CPU 时间配额",
        )
        .forEach { (field, label) -> DataRow(label, if (report.getBoolean(field)) "支持" else "不支持") }
    DataRow("网络限速程序", if (report.getBoolean("networkLoaded")) "已加载" else "未加载")
    SectionTitle("系统功能")
    val hooks = report.getJSONObject("hookExecution")
    featureNames.forEach { (name, label) ->
        DataRow(label, if (hooks.optBoolean(name)) "已就绪" else "未就绪")
    }
}

internal fun formatBytes(value: Long): String =
    when {
        value >= 1073741824 -> String.format(Locale.getDefault(), "%.2f GiB", value / 1073741824.0)
        value >= 1048576 -> String.format(Locale.getDefault(), "%.2f MiB", value / 1048576.0)
        value >= 1024 -> String.format(Locale.getDefault(), "%.2f KiB", value / 1024.0)
        else -> "$value B"
    }

@Composable
internal fun NetworkCounterView(counter: JSONObject, apps: List<JSONObject>, icons: AppIcons) {
    val uid = counter.getInt("uid")
    val owners = apps.filter { it.getInt("uid") == uid }
    SectionTitle(if (counter.getString("direction") == "upload") "上传" else "下载")
    if (owners.isEmpty()) DataRow("应用组", "UID $uid")
    owners.forEach { app ->
        ListItem(
            headlineContent = { Text(app.getString("label")) },
            supportingContent = { Text(app.getString("package")) },
            leadingContent = { ApplicationIcon(app, icons) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (owners.size > 1) Text("以上应用共享计数", style = MaterialTheme.typography.bodySmall)
    DataRow(
        "已通过",
        "${formatBytes(counter.getLong("passedBytes"))} · ${counter.getLong("passedPackets")} 个包",
    )
    DataRow(
        "已丢弃",
        "${formatBytes(counter.getLong("droppedBytes"))} · ${counter.getLong("droppedPackets")} 个包",
    )
}

private val eventNames =
    mapOf(
        "foreground" to "前后台变化",
        "active-task" to "活动任务变化",
        "process-started" to "进程启动",
        "process-gone" to "进程退出",
        "permissions-changed" to "权限变更",
        "package-changed" to "应用安装或更新",
        "blocked" to "启动已拦截",
        "push-arrived" to "收到推送",
        "task-removed" to "任务已划走",
        "feature-error" to "系统功能异常",
        "rule-record" to "自动化记录",
        "reclaim" to "清理后台",
        "reclaim-skipped-active" to "跳过活动应用",
        "mode.set" to "运行模式更新",
        "app.replace" to "应用策略更新",
        "app.patch" to "应用策略更新",
        "app.inherit" to "恢复应用继承",
        "defaults.patch" to "默认模板更新",
        "config.replace" to "配置更新",
        "rules.replace" to "自动化规则更新",
        "migration.import" to "导入策略",
    )

@Composable
internal fun AuditRecordView(record: JSONObject, apps: List<JSONObject>, icons: AppIcons) {
    val data = record.getJSONObject("data")
    val event = record.getString("event")
    val app = apps.firstOrNull { it.getString("package") == data.optString("package") }
    val time =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.getLong("time")))
    ListItem(
        headlineContent = { Text(eventNames[event] ?: event) },
        supportingContent = { Text(time) },
        leadingContent = app?.let { item -> { ApplicationIcon(item, icons) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (app != null) Text(app.getString("label"))
        else if (data.optString("package").isNotEmpty()) Text(data.getString("package"))
        if (event in listOf("foreground", "active-task"))
            Text(if (data.getBoolean("active")) "进入活动状态" else "退出活动状态")
        if (data.optString("reason").isNotEmpty()) Text("原因：${data.getString("reason")}")
        if (data.has("feature"))
            Text("功能：${featureNames[data.getString("feature")] ?: data.getString("feature")}")
        if (data.has("id")) Text("规则：${data.getString("id")}")
        if (data.optInt("uid", -1) >= 0 && app == null) Text("应用组：${data.getInt("uid")}")
        if (data.optString("proxyState").isNotEmpty()) Text("推送状态：${data.getString("proxyState")}")
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
}
