package dev.local.fuxicontrol.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.fuxicontrol.R
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal object CleanupClient {
    suspend fun execute(request:JSONObject):JSONObject {
        var result=withContext(Dispatchers.IO){Wire.call(request)}
        val id=result.optString("id")
        while(result.optString("state")=="running") {
            delay(300)
            result=withContext(Dispatchers.IO){Wire.call(JSONObject().put("command","cleanup.status"))}
            if(result.optString("id")!=id) throw java.io.IOException("上一任务已结束，当前为另一清理任务；请查看最近事件")
        }
        return result
    }
    fun summary(result:JSONObject):String {
        if(result.optString("state")=="preview") return "当前仅预览，未执行清理"
        if(result.optString("state")=="failed") return "清理失败：${result.optString("error")}"
        val rows=result.optJSONArray("results")?:JSONArray()
        fun count(state:String)=(0 until rows.length()).count{rows.getJSONObject(it).optString("state")==state}
        if(rows.length()==0)return "没有符合规则的后台应用"
        return "已退出 ${count("exited")} · 已请求 ${count("requested")} · 跳过 ${count("skipped")} · 失败 ${count("failed")}"
    }
}

internal fun confirmationFor(result:JSONObject,request:JSONObject):JSONObject? {
    if(request.optString("command") !in listOf("cleanup.app","cleanup.process"))return null
    val rows=result.optJSONArray("results")?:JSONArray()
    return (0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull {
        it.optString("state")=="confirmation-required" && it.optString("package")==request.getString("package") &&
            it.has("pid")==request.has("pid") && (!request.has("pid") ||
            (it.optInt("pid")==request.getInt("pid") && it.optLong("startTicks")==request.getLong("startTicks")))
    }
}

@Composable internal fun CleanupAction(pkg:String="",process:JSONObject?=null,enabled:Boolean=true,menu:Boolean=false) {
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var result by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf("")}
    var expanded by remember{mutableStateOf(false)}
    var details by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf<JSONObject?>(null)}
    var pending by remember{mutableStateOf<JSONObject?>(null)}
    val label=if(pkg.isEmpty())"一键清理" else if(process!=null)"结束进程" else "强制停止"
    fun launch(request:JSONObject) {
        scope.launch {
            busy=true;error="";result=null
            try {
                val next=CleanupClient.execute(request)
                result=next
                val confirmation=confirmationFor(next,request)
                if(confirmation!=null){confirm=confirmation;pending=JSONObject(request.toString())}
            } catch(e:java.io.IOException){error=e.message?:"清理失败"}
            finally{busy=false}
        }
    }
    fun request() {
        val r=JSONObject().put("command",if(pkg.isEmpty())"cleanup.quick" else if(process!=null)"cleanup.process" else "cleanup.app")
        if(pkg.isNotEmpty())r.put("package",pkg)
        if(process!=null)r.put("pid",process.getInt("pid")).put("startTicks",process.getLong("startTicks"))
        launch(r)
    }
    Column {
        if(menu) {
            TextButton(enabled=enabled&&!busy,onClick={expanded=true}){Text(if(busy)"清理中" else "操作")}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                DropdownMenuItem(text={Text(label)},onClick={expanded=false;request()})
            }
        } else if(pkg.isEmpty()) Button(enabled=enabled&&!busy,onClick={request()}){Text(if(busy)"清理中…" else label)}
        else OutlinedButton(enabled=enabled&&!busy,onClick={request()}){Text(if(busy)"清理中…" else label)}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        result?.let {r->TextButton(onClick={details=true}){Text(CleanupClient.summary(r),style=MaterialTheme.typography.bodySmall)}}
    }
    confirm?.let {c->AlertDialog(onDismissRequest={confirm=null;pending=null},title={Text("结束受保护的任务？")},
        text={Text("${c.getString("package")}\n${c.getString("reason")}。继续会中断当前任务，不会修改保留设置。")},
        confirmButton={TextButton(onClick={val r=pending!!;confirm=null;pending=null;launch(r.put("overrideProtection",true))}){Text("仍然结束")}},
        dismissButton={TextButton(onClick={confirm=null;pending=null}){Text("取消")}})}
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("清理结果")},text={
        val rows=result?.optJSONArray("results")?:JSONArray()
        androidx.compose.foundation.lazy.LazyColumn {
            items(rows.length()){i->val r=rows.getJSONObject(i)
                val state=when(r.optString("state")){"exited"->"已退出";"requested"->"已请求结束";"skipped"->"已跳过";"confirmation-required"->"未确认，已保留";else->"失败"}
                ListItem(headlineContent={Text(r.getString("package"))},supportingContent={Text("$state ${r.optString("reason")}")})
            }
        }
    },confirmButton={TextButton(onClick={details=false}){Text("关闭")}})
}

internal object CleanupShortcuts {
    fun info(context:Context):ShortcutInfo = ShortcutInfo.Builder(context,"cleanup")
        .setShortLabel("一键清理").setLongLabel("清理符合规则的后台应用")
        .setIcon(Icon.createWithResource(context,R.drawable.ic_cleanup))
        .setIntent(Intent(context,QuickCleanupActivity::class.java).setAction("dev.local.fuxicontrol.CLEANUP"))
        .build()
    fun publish(context:Context){context.getSystemService(ShortcutManager::class.java).dynamicShortcuts=listOf(info(context))}
}
@Composable internal fun CleanupShortcutSettings() {
    val context=LocalContext.current
    var message by remember{mutableStateOf("")}
    SectionTitle("后台清理快捷入口")
    Text("符合规则的应用将直接强制停止，可能暂停通知，直到再次打开应用。音乐、导航、下载和关键服务默认保留。")
    TextButton(onClick={
        val manager=context.getSystemService(ShortcutManager::class.java)
        message=if(manager.isRequestPinShortcutSupported){manager.requestPinShortcut(CleanupShortcuts.info(context),null);"请在桌面提示中完成添加"}else "当前桌面不支持固定快捷方式"
    }){Text("添加到桌面")}
    TextButton(onClick={context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context,CleanupTileService::class.java),"一键清理",Icon.createWithResource(context,R.drawable.ic_cleanup),context.mainExecutor
    ){code->message=when(code){StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED->"磁贴已添加";StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED->"磁贴已存在";else->"未添加磁贴，可在下拉面板编辑中添加"}}}){Text("添加快捷设置磁贴")}
    if(message.isNotEmpty())Text(message)
}
