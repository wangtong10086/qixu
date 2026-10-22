package dev.local.fuxicontrol.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.local.fuxicontrol.R
import dev.local.fuxicontrol.core.AffinityPolicy
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.*
import org.json.*

class SchedulingActivity:ComponentActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setContent {QixuTheme(window){Scheduling(intent.getStringExtra("package")?:"",intent.getStringExtra("process")?:""){finish()}}}
    }
}
private val phases=linkedMapOf("foreground" to "前台","background" to "后台")
private val cpuNames=linkedMapOf("system" to "系统调度","little" to "小核","big" to "大核","prime" to "超大核")
private fun objects(array:JSONArray)=(0 until array.length()).map{array.getJSONObject(it)}
private fun numbers(array:JSONArray)=(0 until array.length()).map{array.getInt(it)}
@Composable private fun CoreSelector(value:String?,topology:JSONObject,background:Boolean,ceiling:Boolean,onChange:(String?)->Unit) {
    val groups=topology.optJSONObject("groups")?:JSONObject()
    val little=groups.optJSONArray("little")?.let(::numbers)?:emptyList()
    val allowed=if(background&&ceiling)little else topology.optJSONArray("possible")?.let(::numbers)?:emptyList()
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(value==null,{onChange(null)},label={Text("继承")})
        cpuNames.forEach{(key,label)->
            val available=key=="system"||(groups.optJSONArray(key)?.let(::numbers)?.any{it in allowed}==true)
            FilterChip(value==key,{onChange(key)},enabled=available,label={Text(label)})
        }
    }
    val selected=if(value!=null&&!AffinityPolicy.GROUPS.contains(value))AffinityPolicy.parse(value) else emptySet<Int>()
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        (topology.optJSONArray("possible")?.let(::numbers)?:emptyList()).forEach{cpu->
            FilterChip(cpu in selected,{
                val next=selected.toMutableSet();if(!next.add(cpu))next.remove(cpu)
                onChange(if(next.isEmpty())null else AffinityPolicy.format(next))
            },enabled=cpu in allowed,label={Text("CPU $cpu")})
        }
    }
    if(background&&ceiling)Text("后台上限：CPU ${little.joinToString()}；音乐、导航、下载同样适用。",style=MaterialTheme.typography.bodySmall)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Scheduling(pkg:String,initialProcess:String,finish:()->Unit) {
    val scope=rememberCoroutineScope()
    var original by rememberSaveable {mutableStateOf("")}
    var draft by rememberSaveable {mutableStateOf("")}
    var process by rememberSaveable {mutableStateOf(initialProcess)}
    var topology by remember {mutableStateOf(JSONObject())}
    var processes by remember {mutableStateOf(listOf<String>())}
    var threads by remember {mutableStateOf(listOf<JSONObject>())}
    var runtime by remember {mutableStateOf(JSONObject())}
    var error by remember {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf(JSONObject())}
    var leave by remember {mutableStateOf(false)}
    var editThread by remember {mutableStateOf<String?>(null)}
    var editingId by remember {mutableStateOf("")}
    var threadPage by rememberSaveable {mutableStateOf(0)}
    var newProcess by remember {mutableStateOf<String?>(null)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    suspend fun rpc(request:JSONObject)=withContext(Dispatchers.IO){Wire.call(request)}
    fun run(action:suspend ()->Unit) {scope.launch {busy=true;try{action();error=""}catch(e:java.io.IOException){error=e.message?:"服务未连接"}catch(e:IllegalArgumentException){error=e.message?:"规则无效"}finally{busy=false}}}
    suspend fun load() {val value=rpc(JSONObject().put("command","config.get")).toString();original=value;draft=value;topology=rpc(JSONObject().put("command","cpu.topology"));message=""}
    fun target(config:JSONObject):JSONObject {
        if(pkg.isEmpty())return config.getJSONObject("defaults")
        val apps=config.getJSONObject("apps");if(!apps.has(pkg))apps.put(pkg,JSONObject())
        val app=apps.getJSONObject(pkg)
        if(process.isEmpty())return app
        if(!app.has("processes"))app.put("processes",JSONObject())
        val values=app.getJSONObject("processes");if(!values.has(process))values.put(process,JSONObject())
        return values.getJSONObject(process)
    }
    fun change(block:(JSONObject,JSONObject)->Unit){val config=JSONObject(draft);block(target(config),config);draft=config.toString()}
    fun putThreads(next:JSONArray){change{value,_->value.put("threads",next)}}
    fun back(){if(draft!=original)leave=true else finish()}
    BackHandler{back()}
    LaunchedEffect(resumed){if(resumed){try{if(draft==original)load()}catch(e:java.io.IOException){error=e.message?:"服务未连接"}}}
    LaunchedEffect(process){threadPage=0}
    LaunchedEffect(resumed,process){if(resumed&&pkg.isNotEmpty())while(true){
        try{
            val snapshot=rpc(JSONObject().put("command","resources.snapshot").put("package",pkg))
            processes=snapshot.optJSONObject("apps")?.optJSONObject(pkg)?.optJSONArray("processes")?.let(::objects)?.map{it.getString("name")}?.distinct()?:emptyList()
            if(process.isNotEmpty()){
                val result=rpc(JSONObject().put("command","threads.list").put("package",pkg).put("process",process));threads=objects(result.getJSONArray("threads"));runtime=result.getJSONObject("runtime")
            }else runtime=rpc(JSONObject().put("command","cpu.status").put("package",pkg))
        }catch(e:java.io.IOException){error=e.message?:"采样失败"}
        delay(2000)
    }}
    val config=if(draft.isEmpty())JSONObject() else JSONObject(draft)
    val current=if(draft.isEmpty())JSONObject() else target(JSONObject(draft))
    val ceiling=config.optJSONObject("scheduling")?.optBoolean("backgroundSmallCoresOnly")==true
    val rules=current.optJSONArray("threads")?:JSONArray()
    fun mutation()=JSONObject().put("command","config.replace").put("config",JSONObject(draft)).put("previewPackage",pkg).put("expectedRevision",JSONObject(original).getLong("revision"))
    Scaffold(topBar={TopAppBar(title={Text(if(pkg.isEmpty())"默认核心调度" else "高级调度")},navigationIcon={IconButton(onClick={back()}){Icon(painterResource(R.drawable.ic_arrow_left),"返回")}})}){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty()){Text(error,color=MaterialTheme.colorScheme.error);TextButton(onClick={run{load()}}){Text("丢弃修改并重新加载")}}
            if(message.isNotEmpty())Text(message)
            if(draft.isNotEmpty()) {
                if(pkg.isEmpty()) {
                    ListItem(headlineContent={Text("后台只用小核")},supportingContent={Text("应用后台运行时生效，包括活动任务；不改变时间配额与清理规则。")},trailingContent={Switch(ceiling,{enabled->change{_,root->root.getJSONObject("scheduling").put("backgroundSmallCoresOnly",enabled)}})})
                }else{
                    Text(pkg,style=MaterialTheme.typography.titleMedium)
                    Text(if(process.isEmpty())"应用整体规则" else process,style=MaterialTheme.typography.bodyMedium)
                    Text("阶段：${if(runtime.optString("phase")=="foreground")"前台" else "后台"} · ${if(ceiling)"后台小核上限已开启" else "后台核心范围跟随系统"}",style=MaterialTheme.typography.bodySmall)
                    val configured=config.optJSONObject("apps")?.optJSONObject(pkg)?.optJSONObject("processes")?.let{it.keys().asSequence().toList()}?:emptyList()
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(process.isEmpty(),{process=""},label={Text("整个应用")})
                        (processes+configured).distinct().forEach{name->FilterChip(process==name,{process=name},label={Text(name)})}
                    }
                    TextButton(onClick={newProcess=""}){Text("添加进程规则")}
                }
                phases.forEach{(phase,label)->
                    Text(label,style=MaterialTheme.typography.titleMedium)
                    val value=if(process.isEmpty())current.optJSONObject("cpuAffinity")?.optString(phase)?.takeIf{it.isNotEmpty()} else current.optJSONObject(phase)?.optString("cpuAffinity")?.takeIf{it.isNotEmpty()}
                    CoreSelector(value,topology,phase=="background",ceiling){selection->change{item,_->
                        val key=if(process.isEmpty())"cpuAffinity" else phase
                        val field=if(process.isEmpty())phase else "cpuAffinity"
                        val values=item.optJSONObject(key)?:JSONObject()
                        if(selection==null)values.remove(field) else values.put(field,selection)
                        if(values.length()==0)item.remove(key) else item.put(key,values)
                    }}
                }
                if(process.isNotEmpty()){
                    HorizontalDivider();Text("线程规则",style=MaterialTheme.typography.titleMedium)
                    Text("每阶段按顺序匹配；同名线程全部应用。名称来自内核，可能被截断。",style=MaterialTheme.typography.bodySmall)
                    objects(rules).forEachIndexed{index,rule->
                        val match=rule.getJSONObject("match")
                        ListItem(headlineContent={Text(rule.getString("id"))},supportingContent={Text(if(match.getString("type")=="main")"主线程" else "${if(match.getString("type")=="glob")"通配符" else "名称"}：${match.getString("name")}")})
                        Row{
                            TextButton(onClick={editingId=rule.getString("id");editThread=rule.toString()}){Text("编辑")}
                            TextButton(enabled=index>0,onClick={val next=objects(rules).toMutableList();java.util.Collections.swap(next,index,index-1);putThreads(JSONArray(next))}){Text("上移")}
                            TextButton(onClick={putThreads(JSONArray(objects(rules).filterIndexed{i,_->i!=index}))}){Text("删除")}
                        }
                    }
                    OutlinedButton(onClick={editingId="";editThread=JSONObject().put("id","rule-"+System.currentTimeMillis()).put("match",JSONObject().put("type","main")).put("foreground",JSONObject().put("cpuAffinity","system")).toString()}){Text("新增线程规则")}
                    HorizontalDivider();Text("运行线程 · ${threads.size}",style=MaterialTheme.typography.titleMedium)
                    Text("允许核心是调度范围；最近运行核心是一次采样。CPU 比例按整机总算力计。",style=MaterialTheme.typography.bodySmall)
                    val pages=((threads.size+39)/40).coerceAtLeast(1)
                    val visiblePage=threadPage.coerceAtMost(pages-1)
                    Row {TextButton(enabled=visiblePage>0,onClick={threadPage=visiblePage-1}){Text("上一页")};Text("${visiblePage+1} / $pages");TextButton(enabled=visiblePage+1<pages,onClick={threadPage=visiblePage+1}){Text("下一页")}}
                    threads.drop(visiblePage*40).take(40).forEach{thread->
                        val scheduling=thread.optJSONObject("scheduling")
                        ListItem(headlineContent={Text(thread.getString("name"))},supportingContent={Text("TID ${thread.getInt("tid")} · CPU ${if(thread.isNull("cpuPercent"))"待采样" else "%.2f%%".format(thread.getDouble("cpuPercent"))}\n允许：${numbers(thread.getJSONArray("allowed")).joinToString()} · 最近：${thread.getInt("lastCpu")}\n${scheduling?.let{"${sourceLabel(it.optString("source"))} · ${stateLabel(it.optString("state"))}"}?:(if(runtime.optString("state")=="system")"系统调度" else "尚无规则执行记录")}${scheduling?.optString("reason")?.takeIf{it.isNotEmpty()}?.let{"\n$it"}?:""}")})
                        TextButton(onClick={editingId="";editThread=JSONObject().put("id","rule-"+thread.getInt("tid")).put("match",if(thread.getBoolean("main"))JSONObject().put("type","main") else JSONObject().put("type","exact").put("name",thread.getString("name"))).put("foreground",JSONObject().put("cpuAffinity","system")).toString()}){Text("为此线程添加规则")}
                    }
                }
                Button(enabled=!busy&&draft!=original,onClick={run{preview=rpc(mutation().put("dryRun",true));confirm=true}}){Text("检查并保存")}
                OutlinedButton(enabled=!busy,onClick={change{value,_->if(process.isEmpty())value.remove("cpuAffinity") else {phases.keys.forEach{value.optJSONObject(it)?.remove("cpuAffinity")};value.remove("threads")}}}){Text("恢复此层核心规则继承")}
            }
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("保存核心调度")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(if(pkg.isEmpty())"将修改当前方案的默认规则和后台上限。" else "将修改 $pkg 的核心调度。")
        val report=preview.optJSONObject("schedulingPreview")?:JSONObject()
        val appReports=report.optJSONObject("apps")?:JSONObject()
        val values=appReports.keys().asSequence().map{appReports.getJSONObject(it)}.toList()
        Text("预检 ${report.optInt("applications")} 个应用、${values.sumOf{it.optInt("threads")}} 个线程；${values.sumOf{it.optInt("restricted")}} 个受系统范围限制，${values.sumOf{it.optInt("blocked")}} 个没有可用核心。")
        objects(preview.optJSONArray("changes")?:JSONArray()).forEach{item->
            val value=item.opt("after")
            Text("${item.getString("path")}：${if(value is JSONObject||value is JSONArray)"更新规则集合" else if(value==JSONObject.NULL)"恢复继承" else value}",style=MaterialTheme.typography.bodySmall)
        }
    }},confirmButton={TextButton(onClick={confirm=false;run{val result=rpc(mutation());original=result.getJSONObject("config").toString();draft=original;message="配置已保存；执行状态将在校准后刷新"}}){Text("保存更改")}},dismissButton={TextButton(onClick={confirm=false}){Text("继续编辑")}})
    if(leave)AlertDialog(onDismissRequest={leave=false},title={Text("放弃未保存修改？")},confirmButton={TextButton(onClick={finish()}){Text("放弃并返回")}},dismissButton={TextButton(onClick={leave=false}){Text("继续编辑")}})
    if(newProcess!=null)AlertDialog(onDismissRequest={newProcess=null},title={Text("完整进程名称")},text={OutlinedTextField(newProcess!!,{newProcess=it},singleLine=true,label={Text("例如 $pkg:worker")})},confirmButton={TextButton(enabled=!newProcess.isNullOrBlank(),onClick={process=newProcess!!.trim();newProcess=null}){Text("编辑规则")}})
    if(editThread!=null)ThreadEditor(editThread!!,topology,ceiling,{editThread=null}){value->
        val next=objects(rules).toMutableList();val index=next.indexOfFirst{it.getString("id")==editingId}
        if(index>=0)next[index]=value else next.add(value)
        putThreads(JSONArray(next));editThread=null
    }
}
private fun sourceLabel(source:String)=when {
    source=="default"->"默认模板"
    source=="app"->"应用规则"
    source=="process"->"进程规则"
    source.startsWith("thread:")->"线程规则 ${source.removePrefix("thread:")}"
    source.startsWith("shared-uid:")->"同 UID 应用规则"
    else->source
}
private fun stateLabel(state:String)=when(state){"applied"->"已生效";"restricted"->"受系统范围限制";"system"->"系统调度";"blocked"->"暂无可用核心";"error"->"执行失败";"exited"->"线程已退出";else->state}
@Composable private fun ThreadEditor(initial:String,topology:JSONObject,ceiling:Boolean,dismiss:()->Unit,save:(JSONObject)->Unit) {
    var draft by remember(initial){mutableStateOf(initial)}
    var error by remember{mutableStateOf("")}
    val value=JSONObject(draft);val match=value.getJSONObject("match")
    fun edit(block:(JSONObject)->Unit){val next=JSONObject(draft);block(next);draft=next.toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text("线程核心规则")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(value.getString("id"),{input->edit{it.put("id",input)}},label={Text("规则 ID")},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState())){mapOf("main" to "主线程","exact" to "名称","glob" to "通配符").forEach{(type,label)->FilterChip(match.getString("type")==type,{edit{it.put("match",JSONObject().put("type",type).also{m->if(type!="main")m.put("name",match.optString("name",""))})}},label={Text(label)})}}
        if(match.getString("type")!="main")OutlinedTextField(match.getString("name"),{input->edit{it.getJSONObject("match").put("name",input)}},label={Text("内核线程名称或模式")},singleLine=true)
        phases.forEach{(phase,label)->Text(label,style=MaterialTheme.typography.titleSmall);CoreSelector(value.optJSONObject(phase)?.optString("cpuAffinity"),topology,phase=="background",ceiling){selection->edit{if(selection==null)it.remove(phase) else it.put(phase,JSONObject().put("cpuAffinity",selection))}}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={try{AffinityPolicy.threads(JSONArray().put(value));save(value)}catch(e:IllegalArgumentException){error=e.message?:"规则无效"}}){Text("完成")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
