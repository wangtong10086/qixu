package dev.local.fuxicontrol.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.local.fuxicontrol.R
import dev.local.fuxicontrol.service.Wire
import dev.local.fuxicontrol.core.ProcessPolicy
import kotlinx.coroutines.*
import org.json.*
import java.util.Locale

class ResourceActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QixuTheme(window) {
            Resources(intent.getStringExtra("package") ?: "") { finish() }
        } }
    }
}
private fun rows(a:JSONArray)= (0 until a.length()).map {a.getJSONObject(it)}
private fun format(value:Double,unit:String):String {
    if(!value.isFinite())return "暂无数据"
    if(unit=="%")return String.format(Locale.ROOT,"%.1f%%",value)
    val divisor=if(value>=1048576)1048576.0 else if(value>=1024)1024.0 else 1.0
    val prefix=if(divisor==1048576.0)"MiB" else if(divisor==1024.0)"KiB" else "B"
    return String.format(Locale.ROOT,"%.1f %s%s",value/divisor,prefix,unit)
}
internal fun resourceDescription(value:JSONObject?):String = if(value==null)"资源数据待读取" else
    "CPU ${format(value.optDouble("cpuPercent"),"%")} · 内存 ${format(value.optDouble("rssBytes"),"")}"
private val metrics=linkedMapOf("cpuPercent" to "CPU","rssBytes" to "内存 RSS","uploadBytesPerSecond" to "上传","downloadBytesPerSecond" to "下载","readBytesPerSecond" to "磁盘读取","writeBytesPerSecond" to "磁盘写入")
private val limits=linkedMapOf("cpuNice" to "CPU 优先级 · 0–19","uclampMax" to "调度上限 · 1–1024","memoryHighMiB" to "内存回收阈值 · MiB","memoryMaxMiB" to "内存硬上限 · MiB","uploadBps" to "上传限速 · bit/s","downloadBps" to "下载限速 · bit/s")

@Composable private fun ResourceValues(value:JSONObject,system:Boolean=false) {
    if(system) {
        Text("可用内存 ${format(value.optDouble("memoryAvailableBytes"),"")} / ${format(value.optDouble("memoryTotalBytes"),"")}")
        Text("Swap 已用 ${format(value.optDouble("swapUsedBytes"),"")} / ${if(value.has("swapTotalBytes")) formatBytes(value.getLong("swapTotalBytes")) else "总量暂无数据"}")
    }
    metrics.forEach { (key,label)->
        if(!(system && key=="rssBytes")) ListItem(headlineContent={Text(label)},trailingContent={Text(format(value.optDouble(key),if(key=="cpuPercent")"%" else if(key.endsWith("PerSecond"))"/s" else ""))})
    }
    if(system) {
        var interfacesExpanded by remember {mutableStateOf(false)}
        TextButton(onClick={interfacesExpanded=!interfacesExpanded}){Text(if(interfacesExpanded)"收起网络接口" else "查看网络接口")}
        if(interfacesExpanded) {
            Text("整机速率仅合计外部网络；虚拟接口不重复计入。以下为开机累计字节。",style=MaterialTheme.typography.bodySmall)
            rows(value.optJSONArray("interfaces")?:JSONArray()).filter{it.optBoolean("physical")||it.optLong("downloadBytes")>0||it.optLong("uploadBytes")>0}.forEach {iface->
                ListItem(headlineContent={Text(iface.getString("name"))},supportingContent={Text("${if(iface.getBoolean("physical"))"计入整机" else "单列接口"} · 上行 ${format(iface.getDouble("uploadBytes"),"")} · 下行 ${format(iface.getDouble("downloadBytes"),"")}")})
            }
        }
    }
    if(value.has("memoryCurrentBytes")) Text("内存计费 ${format(value.getDouble("memoryCurrentBytes"),"")}")
    if(value.has("uploadDroppedBytes"))Text("限速丢弃：上传 ${format(value.optDouble("uploadDroppedBytes"),"")} · 下载 ${format(value.optDouble("downloadDroppedBytes",0.0),"")}")
}
@Composable private fun LimitFields(text:String,inherited:JSONObject=JSONObject(),onChange:(String)->Unit) {
    val values=JSONObject(text)
    Text("进程 CPU 时间配额",style=MaterialTheme.typography.titleMedium)
    CpuQuotaFields(values.optJSONObject("cpuQuota")?:dev.local.fuxicontrol.core.CpuQuota.defaults()) {quota->onChange(JSONObject(text).put("cpuQuota",quota).toString())}
    TextButton(onClick={val next=JSONObject(text);next.remove("cpuQuota");onChange(next.toString())}) {Text("移除进程额外配额")}
    limits.forEach { (key,label)->
        OutlinedTextField(if(values.has(key))values.get(key).toString() else "",{input->
            val next=JSONObject(text);if(input.isBlank())next.remove(key) else next.put(key,input);onChange(next.toString())
        },label={Text(label)},placeholder={Text(if(inherited.has(key))"继承：${inherited.get(key)}" else "继承")},singleLine=true,modifier=Modifier.fillMaxWidth())
    }
    Text("留空继承；内存和网络填 0 取消进程额外限制。应用总预算仍然有效。",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("" to "继承网络","allow" to "允许联网","background-deny" to "后台断网","deny" to "禁止联网").forEach {(key,label)->
            FilterChip(values.optString("network")==key,{val next=JSONObject(text);if(key.isEmpty())next.remove("network") else next.put("network",key);onChange(next.toString())},label={Text(label)})
        }
    }
}
private fun normalizedLimits(value:JSONObject):JSONObject {
    val result=JSONObject(value.toString())
    limits.keys.forEach { if(result.has(it))result.put(it,result.get(it).toString().toLong()) }
    return result
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Resources(initialPackage:String,finish:()->Unit) {
    val context=LocalContext.current
    val coroutine=rememberCoroutineScope()
    val icons=remember{AppIcons()}
    var pkg by rememberSaveable {mutableStateOf(initialPackage)}
    var process by rememberSaveable {mutableStateOf("")}
    var section by rememberSaveable {mutableStateOf("资源")}
    var snapshot by remember {mutableStateOf(JSONObject())}
    var apps by remember {mutableStateOf(listOf<JSONObject>())}
    var config by remember {mutableStateOf(JSONObject())}
    var error by remember {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var search by rememberSaveable {mutableStateOf("")}
    var sort by rememberSaveable {mutableStateOf("cpuPercent")}
    var span by rememberSaveable {mutableStateOf(0)}
    var points by remember {mutableStateOf(listOf<JSONObject>())}
    var oldestTime by remember {mutableStateOf(0L)}
    var processTab by rememberSaveable {mutableStateOf("资源")}
    var details by remember {mutableStateOf(JSONObject())}
    var editRevision by rememberSaveable {mutableStateOf(0L)}
    var stage by rememberSaveable {mutableStateOf("background")}
    var draft by rememberSaveable {mutableStateOf("{}")}
    var original by rememberSaveable {mutableStateOf("{}")}
    var selected by rememberSaveable {mutableStateOf(listOf<String>())}
    var batch by rememberSaveable {mutableStateOf(false)}
    var confirmation by remember {mutableStateOf("")}
    var history by remember {mutableStateOf(true)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    suspend fun rpc(request:JSONObject)=withContext(Dispatchers.IO){Wire.call(request)}
    fun run(command:JSONObject,done:(JSONObject)->Unit={}) {
        coroutine.launch {
            busy=true
            try {done(rpc(command));error=""} catch(e:java.io.IOException) {error=e.message?:"本地服务连接失败"} catch(e:IllegalArgumentException){error=e.message?:"输入无效"}
            finally{busy=false}
        }
    }
    fun back() {
        if(draft!=original){confirmation="leave";return}
        if(batch){batch=false;return}
        if(process.isNotEmpty()){process="";section="进程";return}
        if(pkg.isNotEmpty() && initialPackage.isEmpty()){pkg="";return}
        finish()
    }
    BackHandler {back()}
    LaunchedEffect(resumed) {
        if(resumed) {
            try {
                apps=rows(rpc(JSONObject().put("command","apps.index")).getJSONArray("apps"))
                config=rpc(JSONObject().put("command","config.get"))
                history=rpc(JSONObject().put("command","resources.settings")).getBoolean("history")
            } catch(e:java.io.IOException){error=e.message?:"连接失败"}
            while(true) {
                try {snapshot=rpc(JSONObject().put("command","resources.snapshot"))} catch(e:java.io.IOException){error=e.message?:"连接失败"}
                delay(2000)
            }
        }
    }
    LaunchedEffect(pkg,process,config.toString()) {
        if(draft==original && !batch) {
            draft=(config.optJSONObject("apps")?.optJSONObject(pkg)?.optJSONObject("processes")?.optJSONObject(process)?:JSONObject()).toString()
            original=draft;editRevision=config.optLong("revision")
        }
    }
    LaunchedEffect(resumed,pkg,process) {
        details=JSONObject()
        if(resumed && pkg.isNotEmpty())while(true) {
            if(snapshot.optJSONObject("apps")?.has(pkg)==true) {
                try {details=rpc(JSONObject().put("command","resources.detail").put("package",pkg).put("process",process))}
                catch(e:java.io.IOException) {error=e.message?:"详情读取失败"}
            }
            delay(10000)
        }
    }
    val app=snapshot.optJSONObject("apps")?.optJSONObject(pkg)
    val live=app?.optJSONArray("processes")?.let(::rows)?:emptyList()
    val current=if(pkg.isEmpty())snapshot.optJSONObject("system") else if(process.isEmpty())app else live.firstOrNull{it.getString("name")==process}
    LaunchedEffect(span,pkg,process) {
        points=emptyList();oldestTime=0
        if(span>0) {
            val scope=if(pkg.isEmpty())"system" else if(process.isEmpty())"app" else "process"
            run(JSONObject().put("command","resources.history").put("scope",scope).put("identity",if(process.isEmpty())pkg else "$pkg/$process").put("since",System.currentTimeMillis()-span*86400000L)) {points=rows(it.getJSONArray("samples")).reversed();oldestTime=it.optLong("oldestTime")}
        }
    }
    LaunchedEffect(snapshot,pkg,process) {if(span==0 && current!=null)points=(points+JSONObject(current.toString()).put("time",snapshot.optLong("capturedAt"))).takeLast(120)}
    val chartMetric=if(pkg.isEmpty() && sort=="rssBytes")"memoryAvailableBytes" else sort
    val chartLabel=if(chartMetric=="memoryAvailableBytes")"可用内存" else metrics[sort]
    val chartUnit=if(sort=="cpuPercent")"%" else if(sort.endsWith("PerSecond"))"/s" else ""
    val label=apps.firstOrNull{it.getString("package")==pkg}?.getString("label")?:pkg
    Scaffold(topBar={TopAppBar(title={Text(if(batch)"批量资源设置" else if(process.isNotEmpty())process else if(pkg.isNotEmpty())label else "资源总览")},navigationIcon={IconButton(onClick={back()}){Icon(painterResource(R.drawable.ic_arrow_left),"返回")}})}) {padding->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(error.isNotEmpty())item {
                Text(error,color=MaterialTheme.colorScheme.error)
                TextButton(onClick={run(JSONObject().put("command","config.get")){config=it;draft=original;message="已重新加载配置"}}){Text("重新加载")}
            }
            if(message.isNotEmpty())item{Text(message)}
            if(snapshot.optString("error").isNotEmpty())item{Text("采样失败：${snapshot.getString("error")}",color=MaterialTheme.colorScheme.error)}
            if(snapshot.optLong("ageMs")>10000)item{Text("以上为上次快照，等待刷新")}
            if(batch) {
                item{Text("将修改 ${selected.size} 个应用；未填写字段保持原值。")}
                item{LimitFields(draft){draft=it}}
                item{Button(enabled=!busy,onClick={confirmation="batch"}){Text("检查并保存")}}
            } else {
                if(pkg.isNotEmpty() && process.isEmpty())item {
                    Row {
                        listOf("资源","进程","策略").forEach{tab->FilterChip(section==tab,{
                            if(tab=="策略") {context.startActivity(Intent(context,MainActivity::class.java).putExtra("editPackage",pkg))} else section=tab
                        },label={Text(tab)})}
                    }
                }
                if(process.isNotEmpty())item {OutlinedButton(enabled=draft==original,onClick={context.startActivity(Intent(context,SchedulingActivity::class.java).putExtra("package",pkg).putExtra("process",process))}){Text("线程与核心调度")}}
                if(process.isNotEmpty())item {Row {listOf("资源","限制").forEach{tab->FilterChip(processTab==tab,{processTab=tab},label={Text(tab)})}}}
                if((section!="进程" || process.isNotEmpty() || pkg.isEmpty()) && (process.isEmpty() || processTab=="资源")) {
                    item {Row {listOf(0 to "实时",1 to "24 小时",7 to "七天").forEach{(days,name)->FilterChip(span==days,{span=days},label={Text(name)})}}}
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {metrics.forEach{(key,name)->FilterChip(sort==key,{sort=key},label={Text(name)})}}
                        val line=MaterialTheme.colorScheme.primary
                        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                            val valid=points.map{it.optDouble(chartMetric)}.filter{it.isFinite()};val max=(valid.maxOrNull()?:1.0).coerceAtLeast(1.0)
                            val start=points.firstOrNull()?.optLong("time")?:0L;val end=points.lastOrNull()?.optLong("time")?:start
                            points.zipWithNext().forEach{(a,b)->
                                val av=a.optDouble(chartMetric);val bv=b.optDouble(chartMetric)
                                if(av.isFinite()&&bv.isFinite()&&b.optLong("time")-a.optLong("time")<if(span==0)10000 else maxOf(a.optInt("periodSeconds",60),b.optInt("periodSeconds",60))*1500L) {
                                    fun point(p:JSONObject,v:Double)=Offset(((p.optLong("time")-start).toDouble()/(end-start).coerceAtLeast(1)*size.width).toFloat(),size.height-(v/max*size.height).toFloat())
                                    drawLine(line,point(a,av),point(b,bv),3.dp.toPx())
                                }
                            }
                        }
                        Text(if(points.isEmpty())"尚无该时段的采样" else "$chartLabel · ${points.size} 个采样点 · 峰值 ${format(points.map{it.optDouble(chartMetric)}.filter{it.isFinite()}.maxOrNull()?:Double.NaN,chartUnit)}",style=MaterialTheme.typography.bodySmall)
                        if(points.size>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            val timeFormat=java.text.SimpleDateFormat(if(span==7)"MM-dd HH:mm" else "HH:mm:ss",Locale.getDefault())
                            Text(timeFormat.format(java.util.Date(points.first().getLong("time"))),style=MaterialTheme.typography.labelSmall)
                            Text(timeFormat.format(java.util.Date(points.last().getLong("time"))),style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    if(span>0 && oldestTime>0)item{Text("本地记录始于 ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(oldestTime))}",style=MaterialTheme.typography.bodySmall)}
                    item{if(current!=null)ResourceValues(current,pkg.isEmpty()) else Text(if(process.isNotEmpty())"此进程当前未运行" else "等待采样")
                        val pss=details.optJSONArray("processes")?.let(::rows)
                        if(!pss.isNullOrEmpty()) {
                            Text("内存 PSS ${format(pss.sumOf{it.getDouble("pssBytes")},"")}")
                            if(process.isNotEmpty()) {
                                val actual=pss.first();val active=actual.getJSONObject("effective")
                                Text("${if(active.getString("stage")=="foreground")"前台" else "后台"}规则 · ${if(active.getBoolean("exempt"))"活动保护中" else modeLabel(active.getString("mode"))}")
                                val quota=actual.optJSONObject("appCpuQuota")
                                Text("应用 CPU 配额：${when(quota?.optString("state")){"applied"->"已生效";"off"->"关闭";"exempt"->"活动保护中";else->"待验证"}}")
                                if(quota?.optString("reason")?.isNotEmpty()==true) Text(quota.getString("reason"),style=MaterialTheme.typography.bodySmall)
                                if(actual.has("cpuMax")) Text("进程 CPU 时间 / 周期：${actual.getString("cpuMax")} µs")
                                actual.optJSONObject("cpuStat")?.let{Text("节流 ${it.optLong("nr_throttled")} 次 · ${it.optLong("throttled_usec")/1000} ms")}
                                Text("主线程优先级 ${actual.getInt("nice")} · 调度上限 ${actual.getInt("uclampMax")}")
                                Text("内存硬上限：${if(actual.getString("memoryMax")=="max")"未单独限制" else format(actual.getString("memoryMax").toDouble(),"")}")
                            }
                        }
                    }
                }
                if(pkg.isEmpty()) {
                    item {
                        Row {Text("记录七天趋势",Modifier.weight(1f));Switch(history,{enabled->run(JSONObject().put("command","resources.settings").put("history",enabled)){history=enabled}})}
                        TextButton(onClick={confirmation="clear"}){Text("清空资源历史")}
                        OutlinedTextField(search,{search=it},label={Text("搜索应用或包名")},modifier=Modifier.fillMaxWidth())
                        if(selected.isNotEmpty())Button(onClick={batch=true;draft="{}";original="{}";editRevision=config.getLong("revision")}){Text("调整 ${selected.size} 个应用")}
                    }
                    val measured=snapshot.optJSONObject("apps")?:JSONObject()
                    items(apps.filter{it.getString("label").contains(search,true)||it.getString("package").contains(search,true)}.sortedByDescending{measured.optJSONObject(it.getString("package"))?.optDouble(sort,0.0)?:0.0},key={it.getString("package")}){info->
                        val name=info.getString("package")
                        Row {
                            Checkbox(name in selected,{checked->selected=if(checked)selected+name else selected-name})
                            Column(Modifier.weight(1f)) {Entry(info.getString("label"),resourceDescription(measured.optJSONObject(name)),leading={ApplicationIcon(info,icons)}){pkg=name;section="资源"}}
                        }
                    }
                } else if(process.isEmpty() && section=="进程") {
                    val rules=config.optJSONObject("apps")?.optJSONObject(pkg)?.optJSONObject("processes")?:JSONObject()
                    val names=(live.map{it.getString("name")}+rules.keys().asSequence().toList()).distinct()
                    items(names){name->
                        val p=live.firstOrNull{it.getString("name")==name}
                        Entry(name,(if(p==null)"未运行" else "PID ${p.getInt("pid")} · ${resourceDescription(p)}")+if(rules.has(name))" · 有独立规则" else " · 继承应用"){process=name;processTab="资源"}
                    }
                    if(names.isEmpty())item{Text("当前未发现运行进程或已保存规则")}
                } else if(process.isNotEmpty() && processTab=="限制") {
                    item {
                        SectionTitle("进程限制")
                        Row {listOf("foreground" to "前台","background" to "后台").forEach{(key,name)->FilterChip(stage==key,{stage=key},label={Text(name)})}}
                        LimitFields(JSONObject(draft).optJSONObject(stage)?.toString()?:"{}",ProcessPolicy.effective(dev.local.fuxicontrol.core.Policy.effective(config,pkg,apps.first{it.getString("package")==pkg}.getBoolean("protected")),JSONObject(),stage=="foreground",false)) {value->draft=JSONObject(draft).put(stage,JSONObject(value)).toString()}
                        Row {Text("音乐、导航、下载时豁免 CPU／内存",Modifier.weight(1f));Switch(JSONObject(draft).optBoolean("exemptActiveTasks",true),{draft=JSONObject(draft).put("exemptActiveTasks",it).toString()})}
                        Text("应用总预算仍生效；前台指所属应用前台或可见。CPU 调度上限不是时间硬配额。",style=MaterialTheme.typography.bodySmall)
                        Row {OutlinedButton(enabled=!busy,onClick={confirmation="inherit"}){Text("恢复继承")};Button(enabled=!busy && draft!=original,onClick={confirmation="save"}){Text("保存更改")}}
                    }
                }
            }
        }
    }
    if(confirmation.isNotEmpty())AlertDialog(onDismissRequest={confirmation=""},title={Text(when(confirmation){"leave"->"放弃未保存的更改？";"clear"->"清空七天资源历史？";"inherit"->"恢复此进程继承？";else->"确认资源限制"})},text={Text(if(confirmation=="batch")"修改 ${selected.size} 个应用。已有进程规则保留，应用总预算会影响其全部进程。" else if(confirmation=="save")"保存 $process 的前后台规则。降低内存硬上限可能导致进程退出；前台限制可能影响当前功能。" else "此操作仅影响当前选择。")},dismissButton={TextButton(onClick={confirmation=""}){Text("取消")}},confirmButton={TextButton(onClick={
        val action=confirmation;confirmation=""
        if(action=="leave"){draft=original;if(process.isNotEmpty()){process="";section="进程"}else if(batch)batch=false else finish()}
        else if(action=="clear")run(JSONObject().put("command","resources.clear")){points=emptyList();message="历史已清空"}
        else try {
            val request=JSONObject().put("expectedRevision",editRevision)
            if(action=="batch")request.put("command","apps.batch.patch").put("packages",JSONArray(selected)).put("patch",normalizedLimits(JSONObject(draft)))
            else {
                request.put("command",if(action=="inherit")"process.policy.inherit" else "process.policy.patch").put("package",pkg).put("process",process)
                if(action=="save") {
                    val rule=JSONObject(draft)
                    listOf("foreground","background").forEach{if(rule.has(it))rule.put(it,normalizedLimits(rule.getJSONObject(it)))}
                    ProcessPolicy.validate(JSONObject().put(process,rule));request.put("rule",rule)
                }
            }
            run(request){config=it.getJSONObject("config");draft=if(action=="inherit"||action=="batch")"{}" else draft;original=draft;batch=false;message="已保存"}
        } catch(e:IllegalArgumentException){error="输入无效：${e.message}"}
    }){Text("确认")}})
}

@Composable internal fun ResourceOverview(open:()->Unit) {
    var value by remember{mutableStateOf<JSONObject?>(null)}
    var failure by remember{mutableStateOf("")}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember{mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    // Expire the visible sample even while a Binder request is waiting for its timeout.
    LaunchedEffect(resumed) {
        if(resumed) while(true) {
            now=android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    LaunchedEffect(resumed) {
        if(resumed)while(true){
            try {
                value=withContext(Dispatchers.IO){Wire.call(JSONObject().put("command","resources.snapshot"))}
                failure=value!!.optString("error")
            } catch(e:java.io.IOException){failure="服务未连接"}
            delay(2000)
        }
    }
    val system=value?.optJSONObject("system")
    val captured=value?.optLong("capturedAtElapsedMs",0) ?: 0
    val fresh=failure.isEmpty() && captured>0 && now-captured<=5000
    val state=when {
        failure.isNotEmpty()->if(failure=="服务未连接") failure else "采样暂不可用"
        system==null->"等待资源采样"
        !fresh->"数据已过期"
        else->""
    }
    ResourceDashboard(system,fresh,state,open)
}
