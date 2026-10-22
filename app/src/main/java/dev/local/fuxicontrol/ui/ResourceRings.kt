package dev.local.fuxicontrol.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

private fun percentage(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"

@Composable
internal fun ResourceDashboard(system: JSONObject?, fresh: Boolean, state: String, open: () -> Unit) {
    fun metric(name: String) = system?.optDouble(name)?.takeIf { fresh && it.isFinite() }
    fun capacity(used: Double?, total: Double?) =
        "${used?.let { formatBytes(it.toLong()) } ?: "—"} / ${total?.let { formatBytes(it.toLong()) } ?: "—"}"
    fun speed(name: String) = metric(name)?.let { "${formatBytes(it.toLong())}/s" } ?: "—"
    val cpu = metric("cpuPercent")
    val total = metric("memoryTotalBytes")
    val available = metric("memoryAvailableBytes")
    val used = if (total != null && available != null) total - available else null
    val memory = if (total != null && total > 0 && used != null) used / total * 100 else null
    val swapTotal = metric("swapTotalBytes")
    val swapUsed = metric("swapUsedBytes")
    val swap = if (swapTotal != null && swapTotal > 0 && swapUsed != null) swapUsed / swapTotal * 100 else null
    val swapLabel = if (swapTotal == 0.0) "Swap 未启用 · 0 B" else "Swap ${percentage(swap)}"
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("资源总览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = open) { Text("查看详情") }
        }
        if (state.isNotEmpty()) Text(state, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 320.dp || fontScale >= 1.5f
            val cpuChart: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.clickable(role = Role.Button, onClickLabel = "查看资源详情", onClick = open)
                    .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageRing(cpu, colors.primary, "CPU", fontScale = fontScale)
                    Text("CPU", style = MaterialTheme.typography.titleSmall)
                    Text("整机使用率", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            val memoryChart: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.clickable(role = Role.Button, onClickLabel = "查看资源详情", onClick = open)
                    .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageRing(memory, colors.primary, "内存", swap, colors.tertiary, fontScale)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(7.dp)) { drawCircle(colors.primary) }
                        Text("内存", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(capacity(used, total), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(7.dp)) { drawCircle(colors.tertiary) }
                        Text(swapLabel, style = MaterialTheme.typography.titleSmall)
                    }
                    if (swapTotal != 0.0) Text(capacity(swapUsed, swapTotal), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                cpuChart(Modifier.fillMaxWidth())
                memoryChart(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cpuChart(Modifier.weight(1f))
                memoryChart(Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("上传", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(speed("uploadBytesPerSecond"), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f)) {
                Text("下载", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(speed("downloadBytesPerSecond"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun UsageRing(value: Double?, color: Color, label: String, inner: Double? = null,
                      innerColor: Color? = null, fontScale: Float) {
    val duration = if (ValueAnimator.areAnimatorsEnabled()) 450 else 0
    val progress by animateFloatAsState((value ?: 0.0).toFloat() / 100f, tween(duration), label = label)
    val innerProgress by animateFloatAsState((inner ?: 0.0).toFloat() / 100f, tween(duration), label = "Swap")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(136.dp * fontScale.coerceAtLeast(1f)).clearAndSetSemantics {
        contentDescription = "$label ${percentage(value)}"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            fun ring(inset: Float, amount: Float?, tint: Color) {
                val diameter = size.minDimension - inset * 2
                val bounds = Size(diameter, diameter)
                val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                drawArc(track, -90f, 360f, false, origin, bounds, style = Stroke(stroke))
                if (amount != null) drawArc(tint, -90f, amount.coerceIn(0f, 1f) * 360f, false,
                    origin, bounds, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            ring(stroke / 2, if (value == null) null else progress, color)
            if (innerColor != null) ring(stroke / 2 + 15.dp.toPx(), if (inner == null) null else innerProgress, innerColor)
        }
        Text(percentage(value), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
    }
}
