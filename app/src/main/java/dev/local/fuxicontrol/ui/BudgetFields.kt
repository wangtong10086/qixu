package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun CpuQuotaFields(value:JSONObject,enabled:Boolean=true,change:(JSONObject)->Unit) {
    Text("每周期的 CPU 时间由组内所有线程共用。50 / 100 ms 等于半个核心；填 0 关闭该阶段配额。",style=MaterialTheme.typography.bodySmall)
    listOf("periodUs" to "周期", "foregroundUs" to "前台额度", "backgroundUs" to "后台额度").forEach { (key,label)->
        val raw=value.opt(key)?.toString()?:"0"
        val number=raw.toLongOrNull()
        val shown=number?.let{(it/1000.0).toString()}?:raw
        OutlinedTextField(shown,{input->
            val next=JSONObject(value.toString())
            val parsed=input.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(1000))
            val micros=try {parsed?.longValueExact()}catch(_:ArithmeticException){null}
            next.put(key,micros?:input);change(next)
        },enabled=enabled,label={Text("$label · ms")},singleLine=true,isError=number==null,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
    }
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text("关键应用也应用此配额",modifier=Modifier.weight(1f))
        Switch(value.optBoolean("allowProtected",false),{change(JSONObject(value.toString()).put("allowProtected",it))},enabled=enabled)
    }
    Text("保存配置不代表已生效；资源详情显示内核支持和实际执行状态。",style=MaterialTheme.typography.bodySmall)
}
@Composable internal fun ManualCleanupFields(value:JSONObject,template:Boolean,enabled:Boolean,change:(JSONObject)->Unit) {
    Choice(value.getString("mode"),listOf("off" to "不清理","cached" to "回收缓存","force-stop" to "强制停止").filter{!template||it.first!="force-stop"},enabled) {
        change(JSONObject(value.toString()).put("mode",it))
    }
    listOf("backgroundSeconds" to "后台至少 · 秒","cpuPercent" to "30 秒平均 CPU 至少 · %","rssMiB" to "内存 RSS 至少 · MiB").forEach { (key,label)->
        OutlinedTextField(value.get(key).toString(),{change(JSONObject(value.toString()).put(key,it))},enabled=enabled,
            label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    }
    Text("所有启用的条件同时满足才清理；CPU／内存填 0 忽略该条件。音乐、导航、下载及关键服务受保护。",style=MaterialTheme.typography.bodySmall)
}
