package dev.local.fuxicontrol.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Controller() } } }
    }
}

@Composable private fun Controller() {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("正在连接管理服务…") }
    var page by remember { mutableStateOf("状态") }
    var config by remember { mutableStateOf(JSONObject()) }
    var search by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedRevision by remember { mutableStateOf(0L) }
    var editing by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    fun request(value: JSONObject, onResult: (JSONObject) -> Unit = {}) {
        scope.launch {
            pending = true
            try {
                val result = withContext(Dispatchers.IO) { Wire.call(value) }
                output = result.toString(2)
                onResult(result)
            } catch (e: java.io.IOException) { output = "连接或执行失败：${e.message}\n首次使用请通过 root CLI 安装并启动服务。" }
              catch (e: org.json.JSONException) { output = "配置格式错误：${e.message}" }
            finally { pending = false }
        }
    }
    fun load() {
        when(page) {
            "应用" -> request(JSONObject().put("command", "apps")) {
                val list = it.getJSONArray("apps")
                packages = (0 until list.length()).map { index -> list.getJSONObject(index) }.sortedBy { it.getString("label") }
            }
            "默认模板", "自动化", "推送" -> request(JSONObject().put("command", "config.get")) {
                config = it
                editing = when(page) {
                    "默认模板" -> it.getJSONObject("defaults").toString(2)
                    "推送" -> (it.optJSONObject("pushProxy") ?: JSONObject()).toString(2)
                    else -> it.getJSONArray("rules").toString(2)
                }
            }
            else -> request(JSONObject().put("command", "status"))
        }
    }
    LaunchedEffect(page) { load() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fuxi Control", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("状态", "应用", "默认模板", "自动化", "推送").forEach { name ->
                TextButton(onClick = { selected = null; page = name }, enabled = !pending) { Text(name) }
            }
        }
        if(pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        when(page) {
            "应用" -> {
                OutlinedTextField(search, { search=it }, Modifier.fillMaxWidth(), label={Text("搜索应用或包名")},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrect=false))
                LazyColumn(Modifier.weight(1f)) {
                    items(packages.filter { it.getString("package").contains(search,true) || it.getString("label").contains(search,true) }) { app ->
                        TextButton(onClick = {
                            selected = app.getString("package")
                            request(JSONObject().put("command","app.get").put("package",selected)) {
                                selectedRevision = it.getLong("revision")
                                editing = it.getJSONObject("overrides").toString(2)
                            }
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(app.getString("label") + if(app.getBoolean("protected")) " · 关键服务" else "")
                                Text(app.getString("package"),style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            "默认模板", "自动化", "推送" -> {
                Text(if(page=="默认模板") "修改将影响所有继承默认的应用；隐私初始无拒绝清单。" else if(page=="推送") "逐应用配置通知代理；消息内容不进入审计日志。" else "事件、条件、延迟和动作由同一策略核心执行。")
                OutlinedTextField(editing,{editing=it},Modifier.fillMaxWidth().weight(1f),label={Text("JSON 配置")},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrect=false,keyboardType=KeyboardType.Ascii))
                Row {
                    listOf(true to "预览", false to "应用").forEach { (dryRun,label) ->
                        Button(onClick={
                            try {
                                val next=JSONObject(config.toString())
                                when(page) {
                                    "默认模板" -> next.put("defaults",JSONObject(editing))
                                    "推送" -> next.put("pushProxy",JSONObject(editing))
                                    else -> next.put("rules",org.json.JSONArray(editing))
                                }
                                request(JSONObject().put("command","config.replace").put("config",next).put("expectedRevision",config.getLong("revision")).put("dryRun",dryRun)) {
                                    if(!dryRun) config=it.getJSONObject("config")
                                }
                            } catch(e:org.json.JSONException) {output="JSON 格式错误：${e.message}"}
                        },enabled=!pending) {Text(label)}
                    }
                }
                Text(output,Modifier.heightIn(max=180.dp),style=MaterialTheme.typography.bodySmall)
            }
            else -> {
                Row { Button(onClick={load()},enabled=!pending){Text("刷新")}
                    TextButton(onClick={request(JSONObject().put("command","capabilities"))}){Text("能力")}
                    TextButton(onClick={request(JSONObject().put("command","audit"))}){Text("审计")}
                    TextButton(onClick={request(JSONObject().put("command","network.stats"))}){Text("流量")}
                }
                Row {
                    TextButton(onClick={request(JSONObject().put("command","mode.set").put("mode","observe"))}){Text("观察")}
                    TextButton(onClick={request(JSONObject().put("command","mode.set").put("mode","enforce"))}){Text("执行策略")}
                    TextButton(onClick={request(JSONObject().put("command","mode.set").put("mode","disabled"))}){Text("停用策略")}
                }
                LazyColumn(Modifier.weight(1f)) {item {Text(output,style=MaterialTheme.typography.bodySmall)}}
            }
        }
    }
    if(selected!=null) AlertDialog(onDismissRequest={selected=null},title={Text(selected!!)},text={
        Column {
            Text("保存会建立逐应用例外；恢复继承可再次跟随默认模板。")
            OutlinedTextField(editing,{editing=it},Modifier.fillMaxWidth().heightIn(max=400.dp),label={Text("应用策略 JSON")},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrect=false,keyboardType=KeyboardType.Ascii))
            Text(output,Modifier.heightIn(max=120.dp),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={
        try {request(JSONObject().put("command","app.replace").put("package",selected).put("patch",JSONObject(editing)).put("expectedRevision",selectedRevision)) {
            selectedRevision=it.getJSONObject("config").getLong("revision")
        }}
        catch(e:org.json.JSONException){output="JSON 格式错误：${e.message}"}
    }){Text("保存")}},dismissButton={TextButton(onClick={request(JSONObject().put("command","app.inherit").put("package",selected).put("expectedRevision",selectedRevision)) {
        selectedRevision=it.getJSONObject("config").getLong("revision");editing="{}"
    }}){Text("恢复继承")}})
}
