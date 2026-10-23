package dev.local.fuxicontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.*
import org.json.*

@Composable internal fun CleanupPanel() {
    val scope=rememberCoroutineScope()
    val icons=remember{AppIcons()}
    var visible by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var preview by remember{mutableStateOf<JSONObject?>(null)}
    var result by remember{mutableStateOf<JSONObject?>(null)}
    var applications by remember{mutableStateOf<Map<String,JSONObject>>(emptyMap())}
    fun refresh() {scope.launch {
        busy=true;error="";result=null
        try {
            val loaded=withContext(Dispatchers.IO) {
                val apps=Wire.call(JSONObject().put("command","apps.index")).getJSONArray("apps")
                Pair((0 until apps.length()).map{apps.getJSONObject(it)}.associateBy{it.getString("package")},Wire.call(JSONObject().put("command","cleanup.preview")))
            }
            applications=loaded.first;preview=loaded.second
        } catch(e:java.io.IOException) {error=e.message?:"无法预览"}
        finally {busy=false}
    }}
    CleanupAction()
    TextButton(onClick={visible=true;refresh()}) {Text("清理规则与预览")}
    if(visible) AlertDialog(onDismissRequest={visible=false},title={Text("清理后台")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("一键清理会强制停止符合规则的应用，可能暂停通知，直到再次打开。此预览保留各应用原有清理力度。")
            Text("仅清理符合规则的后台应用。执行前再次检查保护状态，不显示估算释放内存。")
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            val list=result?.optJSONArray("results")?:preview?.optJSONArray("apps")?:JSONArray()
            val items=(0 until list.length()).map{list.getJSONObject(it)}
            val count=items.count{it.optBoolean("eligible")}
            Text(if(result!=null) "结果：${result!!.optString("state")}" else "符合规则 $count 个应用")
            if(items.isEmpty()&&!busy) Text("暂无符合条件的后台应用。")
            LazyColumn(Modifier.heightIn(max=360.dp)) {
                items(items,key={it.getString("package")}) {row->
                    val name=row.getString("package")
                    val app=applications[name]?:JSONObject().put("package",name).put("label",name).put("lastUpdateTime",0L)
                    val state=when(row.optString("state")) {"exited"->"已退出";"requested"->"已请求系统回收";"skipped"->"已跳过";"failed"->"失败";else->if(row.optBoolean("eligible"))if(row.optString("action")=="force-stop")"强制停止" else "回收缓存" else "保留"}
                    ListItem(leadingContent={ApplicationIcon(app,icons)},headlineContent={Text(app.optString("label",name))},supportingContent={Text("$state ${row.optString("reason")}")})
                }
            }
            if(result?.optString("error")?.isNotEmpty()==true) Text(result!!.getString("error"),color=MaterialTheme.colorScheme.error)
            Text("CPU 条件需要连续 30 秒采样；首次预览后可稍候刷新。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={refresh()},enabled=!busy){Text("重新预览")}
        }
    },confirmButton={
        val ready=preview!=null && preview!!.optString("mode")=="enforce" && result==null
        Button(enabled=ready&&!busy,onClick={scope.launch {
            busy=true;error=""
            try {
                val p=preview!!
                result=withContext(Dispatchers.IO){Wire.call(JSONObject().put("command","cleanup.run").put("id",p.getString("id")).put("expectedRevision",p.getLong("revision")))}
                while(result!!.optString("state")=="running") {delay(500);result=withContext(Dispatchers.IO){Wire.call(JSONObject().put("command","cleanup.status"))}}
            } catch(e:java.io.IOException) {error=e.message?:"清理状态未知，请查询服务"}
            finally {busy=false}
        }}) {Text("执行清理")}
    },dismissButton={TextButton(onClick={visible=false}){Text("关闭")}})
}
