package dev.local.fuxicontrol.design

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val Purple = Color(0xFF6750A4)
private val Container = Color(0xFFF3EDF7)
private val Ink = Color(0xFF1D1B20)
private val Muted = Color(0xFF625D6B)
private val Green = Color(0xFF276749)
private val ThemeColors = lightColorScheme(
    primary=Purple, onPrimary=Color.White, primaryContainer=Color(0xFFEADDFF), onPrimaryContainer=Color(0xFF21005D),
    surface=Color(0xFFFFFBFE), onSurface=Ink, surfaceVariant=Container, onSurfaceVariant=Muted,
    background=Color(0xFFFFFBFE), onBackground=Ink, outline=Color(0xFF79747E), outlineVariant=Color(0xFFE5DFE9)
)
enum class Variant(val title:String,val summary:String) { A("原生简洁","分组列表 · 舒朗留白"), B("状态概览","执行状态 · 重点信息"), C("高效管理","紧凑列表 · 快速筛选") }
private data class DemoApp(val title:String,val pkg:String,val source:String,val detail:String,val icon:Int)
private val apps = listOf(
    DemoApp("示例音乐","com.example.music","继承默认","播放期间临时放行",R.drawable.ic_music),
    DemoApp("示例下载","com.example.download","有例外","上传 512 kbit/s",R.drawable.ic_download),
    DemoApp("示例导航","com.example.navigation","关键服务","导航期间保持运行",R.drawable.ic_navigation),
    DemoApp("示例阅读","com.example.reader","继承默认","自启动禁止 · 后台平衡",R.drawable.ic_book_open),
    DemoApp("示例笔记","com.example.notes","继承默认","自启动禁止 · 后台平衡",R.drawable.ic_layers)
)
class DesignActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val variant=Variant.entries.firstOrNull { it.name==intent.getStringExtra("variant") } ?: Variant.A
        val page=intent.getStringExtra("page") ?: "概览"
        setContent { DesignTheme { DesignApp(variant,page) } }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
@Composable fun DesignTheme(content:@Composable ()->Unit) { MaterialTheme(colorScheme=ThemeColors, content=content) }
@Composable private fun Glyph(id:Int,description:String?=null,tint:Color=Purple) { Icon(painterResource(id),description,Modifier.size(22.dp),tint=tint) }
@Composable private fun Caption(text:String,color:Color=Muted) { Text(text,style=MaterialTheme.typography.bodySmall,color=color) }
@Composable private fun Section(title:String, trailing:String?=null, compact:Boolean=false) {
    Row(Modifier.fillMaxWidth().padding(top=if(compact) 10.dp else 18.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Purple)
        if(trailing!=null) Caption(trailing)
    }
}
@Composable private fun Tag(text:String,highlight:Boolean=false) {
    Surface(color=if(highlight) MaterialTheme.colorScheme.primaryContainer else Container,shape=RoundedCornerShape(8.dp)) {
        Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=if(highlight) Purple else Muted)
    }
}
@Composable private fun PlainRow(title:String,subtitle:String,value:String?=null,icon:Int?=null,compact:Boolean=false,onClick:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier).padding(vertical=if(compact) 8.dp else 14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        if(icon!=null) Glyph(icon)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge)
            if(subtitle.isNotEmpty()) Caption(subtitle)
        }
        if(value!=null) Text(value,style=MaterialTheme.typography.labelLarge,color=Purple)
        if(onClick!=null) Glyph(R.drawable.ic_chevron_right,tint=Muted)
    }
}
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Surface(color=Container,shape=RoundedCornerShape(24.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp),content=content) }
}
@Composable private fun Metric(value:String,label:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Medium);Caption(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DesignApp(initialVariant:Variant=Variant.A,initialPage:String="概览") {
    var variant by rememberSaveable { mutableStateOf(initialVariant) }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var selected by rememberSaveable { mutableStateOf("com.example.download") }
    var chooseVersion by remember { mutableStateOf(false) }
    var stateSheet by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("执行") }
    var connected by rememberSaveable { mutableStateOf(true) }
    var conflict by rememberSaveable { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() };val scope=rememberCoroutineScope()
    fun toast(text:String) { scope.launch { snackbar.showSnackbar(text) } }
    val detail=page=="应用策略"
    var dirty by rememberSaveable { mutableStateOf(false) }
    var leaveTo by remember { mutableStateOf<String?>(null) }
    fun navigate(target:String) { if(dirty) leaveTo=target else page=target }
    BackHandler(page!="概览") { navigate(if(detail) "应用" else "概览") }
    Scaffold(
        containerColor=ThemeColors.surface,
        topBar={ Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                if(detail) IconButton(onClick={navigate("应用")}) { Glyph(R.drawable.ic_arrow_left,"返回应用") }
                Column(Modifier.weight(1f)) { Text("FUXI / DESIGN LAB",style=MaterialTheme.typography.labelMedium,color=Purple);Caption("设计演示 · 不影响设备策略") }
                TextButton(onClick={chooseVersion=true}) { Text("${variant.name} · ${variant.title}");Spacer(Modifier.width(4.dp));Glyph(R.drawable.ic_sliders_horizontal) }
            }
            HorizontalDivider(color=ThemeColors.outlineVariant)
        } },
        bottomBar={NavigationBar(containerColor=Container,tonalElevation=0.dp) {
            listOf("概览" to R.drawable.ic_layout_dashboard,"应用" to R.drawable.ic_grid_2x2,"策略" to R.drawable.ic_sliders_horizontal,"设置" to R.drawable.ic_settings_2).forEach { (name,icon)->
                val active=page==name || (detail && name=="应用") || (page=="默认模板" && name=="策略")
                NavigationBarItem(selected=active,onClick={navigate(if(name=="策略") "默认模板" else name)},icon={Glyph(icon,tint=if(active) Purple else Muted)},label={Text(name)})
            }
        }},snackbarHost={SnackbarHost(snackbar)}
    ) { padding->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if(!connected) Surface(color=Color(0xFFFCE8E6)) { Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Glyph(R.drawable.ic_wifi_off,tint=Color(0xFFB3261E));Text("服务已断开 · 当前只读",Modifier.weight(1f).padding(8.dp),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={connected=true;toast("演示连接已恢复")}) {Text("重连")}
            }}
            when(page) {
                "概览"->Overview(variant,mode,{mode=it;toast("演示模式：$it")},{navigate("应用")},{navigate("默认模板")})
                "应用"->AppList(variant) { selected=it;page="应用策略" }
                "应用策略"->PolicyEditor(variant,false,selected,connected,conflict,{conflict=false}, {dirty=it},{toast(it)})
                "默认模板","策略"->PolicyEditor(variant,true,selected,connected,conflict,{conflict=false},{dirty=it},{toast(it)})
                else->Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("设置",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(8.dp));Caption("预览工具与设计规范")
                    Section("界面预览")
                    PlainRow("设计版本",variant.summary,variant.name,R.drawable.ic_layers) {chooseVersion=true}
                    HorizontalDivider();PlainRow("交互状态","连接断开、配置冲突与保存反馈",icon=R.drawable.ic_activity) {stateSheet=true}
                    Section("视觉规范")
                    PlainRow("Material Design 3","主色 #6750A4 · 纯色 surface container")
                    PlainRow("Lucide 图标","官方 SVG 路径 · 原生矢量资源")
                    PlainRow("触控与状态反馈","48dp 触控区 · 原生水波纹")
                    Section("说明")
                    Text("所有页面使用同一组演示数据。保存、恢复继承和模式切换仅改变本次预览，不会向 root 服务发送请求。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                }
            }
        }
    }
    if(chooseVersion) ModalBottomSheet(onDismissRequest={chooseVersion=false},containerColor=ThemeColors.surface) {
        Column(Modifier.padding(horizontal=24.dp).padding(bottom=32.dp)) {
            Text("选择设计版本",style=MaterialTheme.typography.headlineSmall);Caption("页面和示例数据保持一致，只比较布局")
            Variant.entries.forEach { v->PlainRow("${v.name} · ${v.title}",v.summary,if(v==variant) "当前" else null,R.drawable.ic_layers) {variant=v;chooseVersion=false} }
        }
    }
    if(stateSheet) ModalBottomSheet(onDismissRequest={stateSheet=false}) {
        Column(Modifier.padding(24.dp)) {
            Text("交互状态演示",style=MaterialTheme.typography.headlineSmall)
            PlainRow("服务断开","设置页变为只读，保留重连入口",icon=R.drawable.ic_wifi_off) {connected=false;page="默认模板";stateSheet=false}
            PlainRow("配置版本冲突","保存时提示重新加载",icon=R.drawable.ic_info) {conflict=true;page="默认模板";stateSheet=false}
        }
    }
    if(leaveTo!=null) AlertDialog(onDismissRequest={leaveTo=null},title={Text("放弃未保存的更改？")},text={Text("本页的演示修改尚未保存。")},confirmButton={TextButton(onClick={dirty=false;page=leaveTo!!;leaveTo=null}) {Text("放弃并离开")}},dismissButton={TextButton(onClick={leaveTo=null}) {Text("继续编辑")}})
}

@Composable private fun Overview(v:Variant,mode:String,setMode:(String)->Unit,openApps:()->Unit,openTemplate:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(top=24.dp,bottom=20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text(if(v==Variant.B) "运行概览" else "Fuxi Control",Modifier.weight(1f),style=MaterialTheme.typography.headlineLarge);Glyph(R.drawable.ic_shield_check)}
        Spacer(Modifier.height(8.dp));Caption(if(v==Variant.C) "策略、例外与最近活动" else "默认禁止自启动 · 后台平衡")
        Spacer(Modifier.height(24.dp))
        val title=when(mode){"执行"->"策略正在执行";"观察"->"仅观察应用行为";else->"策略已停用"}
        if(v==Variant.B) {
            Surface(color=Purple,shape=RoundedCornerShape(28.dp),shadowElevation=2.dp) {Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Glyph(R.drawable.ic_activity,tint=Color.White);Text("服务已连接",style=MaterialTheme.typography.labelLarge,color=Color.White)}
                Spacer(Modifier.height(18.dp));Text(title,style=MaterialTheme.typography.headlineMedium,color=Color.White)
                Spacer(Modifier.height(8.dp));Text("16 / 16 项检查已就绪",style=MaterialTheme.typography.bodyMedium,color=Color(0xFFEADDFF))
            }}
        } else if(v==Variant.C) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Glyph(R.drawable.ic_check,tint=Green);Text(title,Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.titleMedium);Tag("16/16 就绪",true)}
            Spacer(Modifier.height(8.dp));Caption("服务已连接 · 模板与例外已同步")
        } else {
            Panel {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Glyph(R.drawable.ic_shield_check,tint=Green);Column {Text(title,style=MaterialTheme.typography.titleMedium);Caption("服务已连接 · 16/16 项检查就绪")}}}
        }
        Spacer(Modifier.height(if(v==Variant.C) 20.dp else 24.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick=openApps).padding(vertical=8.dp)) {Metric("336","已管理应用",Modifier.weight(1f));Metric("333","继承默认",Modifier.weight(1f));Metric("3","应用例外",Modifier.weight(1f))}
        Section("默认策略","适用于普通应用")
        PlainRow("禁止自启动","后台平衡 · 隐私限制 0 项",icon=R.drawable.ic_sliders_horizontal,onClick=openTemplate)
        HorizontalDivider(color=ThemeColors.outlineVariant)
        Section("运行模式")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("执行","观察","停用").forEach { m->FilterChip(selected=mode==m,onClick={setMode(m)},label={Text(m)},leadingIcon={if(mode==m) Glyph(R.drawable.ic_check)})}}
        Section("最近活动","今天")
        listOf("14:32" to "默认模板已更新","14:28" to "保留 3 个应用例外").forEach { (time,event)->
            Row(Modifier.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {Glyph(R.drawable.ic_clock_3,tint=Muted);Caption(time);Text(event,style=MaterialTheme.typography.bodyMedium)}
        }
        if(v==Variant.C) {Spacer(Modifier.height(12.dp));OutlinedButton(onClick=openApps,modifier=Modifier.fillMaxWidth()) {Glyph(R.drawable.ic_search);Spacer(Modifier.width(8.dp));Text("查找应用策略")}}
    }
}

@Composable private fun AppList(v:Variant,open:(String)->Unit) {
    var search by rememberSaveable {mutableStateOf("")};var filter by rememberSaveable {mutableStateOf("全部")}
    Column(Modifier.fillMaxSize().padding(horizontal=if(v==Variant.C) 16.dp else 24.dp)) {
        Spacer(Modifier.height(24.dp));Row(verticalAlignment=Alignment.CenterVertically) {Text("应用",Modifier.weight(1f),style=MaterialTheme.typography.headlineLarge);Tag("336 个")}
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value=search,onValueChange={search=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("搜索应用或包名")},leadingIcon={Glyph(R.drawable.ic_search)},singleLine=true,shape=RoundedCornerShape(28.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("全部","继承默认","有例外","关键服务").forEach {f->FilterChip(selected=filter==f,onClick={filter=f},label={Text(f)})}}
        Caption("展示 5 个示例应用 · 点击查看策略来源")
        Spacer(Modifier.height(8.dp))
        val shown=apps.filter{(filter=="全部"||it.source==filter)&&(it.title.contains(search,true)||it.pkg.contains(search,true))}
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if(shown.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally) {Glyph(R.drawable.ic_search);Spacer(Modifier.height(12.dp));Text("没有匹配的应用");Caption("试试其他名称或包名")}
            shown.forEach { app->
                Row(Modifier.fillMaxWidth().clickable {open(app.pkg)}.padding(vertical=if(v==Variant.C) 14.dp else 18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=RoundedCornerShape(if(v==Variant.A) 14.dp else 20.dp),color=Container) {Box(Modifier.size(44.dp),contentAlignment=Alignment.Center) {Glyph(app.icon)}}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {Text(app.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);if(v!=Variant.A) Tag(app.source,app.source=="有例外")}
                        Caption(if(v==Variant.C) app.detail else app.pkg)
                        if(v!=Variant.C) Caption(if(v==Variant.A) "${app.source} · ${app.detail}" else app.detail)
                    }
                    if(v==Variant.A) Glyph(R.drawable.ic_chevron_right,tint=Muted)
                }
                HorizontalDivider(color=ThemeColors.outlineVariant)
            }
        }
    }
}

@Composable private fun PolicyEditor(v:Variant,template:Boolean,pkg:String,connected:Boolean,conflict:Boolean,clearConflict:()->Unit,markDirty:(Boolean)->Unit,notify:(String)->Unit) {
    val app=apps.first { it.pkg==pkg }
    var override by rememberSaveable(template,pkg) { mutableStateOf(!template && pkg=="com.example.download") }
    var upload by rememberSaveable(template,pkg) { mutableStateOf(if(override) "512" else "") }
    var cap by rememberSaveable(template,pkg) { mutableStateOf(override) }
    var startup by rememberSaveable(template,pkg) {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)};var conflictDialog by remember {mutableStateOf(false)}
    var restore by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(false)}
    fun save() {
        error=cap && (upload.toLongOrNull()?.let {it>0}!=true)
        if(!error) {if(conflict) conflictDialog=true else confirm=true}
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(top=20.dp,bottom=12.dp)) {
            Text(if(template) "默认模板" else app.title,style=MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp));Caption(if(template) "所有继承默认的应用都会跟随此模板" else pkg)
            Spacer(Modifier.height(16.dp))
            if(v==Variant.B) Panel {Text(if(template) "影响 333 个应用" else if(override) "此应用有独立设置" else "正在继承默认模板",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(6.dp));Caption(if(template) "3 个应用的已有例外将保留" else "下方标记了每项设置的来源")}
            else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {Tag(if(template) "333 个应用继承" else if(override) "逐应用例外" else "继承默认",true);Caption(if(template) "3 个例外保留" else "仅影响此应用")}
            Section("启动与后台",compact=v==Variant.C)
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("允许自启动",style=MaterialTheme.typography.bodyLarge);Caption(if(template) "普通应用默认禁止" else if(startup) "本应用覆盖 · 允许" else "继承默认 · 禁止")};Switch(startup,{startup=it;markDirty(true)},enabled=connected)}
            PlainRow("后台策略","音乐、导航、下载运行时临时放行","平衡",compact=v==Variant.C)
            HorizontalDivider(color=ThemeColors.outlineVariant)
            Section("CPU 与内存",compact=v==Variant.C)
            PlainRow("CPU 优先级",if(template) "仅作用于后台进程" else "继承默认","nice 5",compact=v==Variant.C)
            PlainRow("内存上限",if(template) "默认不设硬限制" else "继承默认","不限",compact=v==Variant.C)
            HorizontalDivider(color=ThemeColors.outlineVariant)
            Section("网络",if(!template && override) "本应用覆盖" else null,compact=v==Variant.C)
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("上传限速",style=MaterialTheme.typography.bodyLarge);Caption(if(cap) "超过上限的流量将丢包" else "默认关闭 · 不限制上传")};Switch(cap,{cap=it;override=true;markDirty(true)},enabled=connected)}
            if(cap) {
                OutlinedTextField(value=upload,onValueChange={upload=it;error=false;markDirty(true)},label={Text("上传速率")},suffix={Text("kbit/s")},modifier=Modifier.fillMaxWidth(),enabled=connected,isError=error,supportingText={Text(if(error) "请输入大于 0 的整数" else "十进制单位 · 512 kbit/s = 64 kB/s")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,shape=RoundedCornerShape(16.dp))
            }
            PlainRow("下载限速",if(template) "默认关闭" else "继承默认","不限",compact=v==Variant.C)
            HorizontalDivider(color=ThemeColors.outlineVariant)
            Section("权限与隐私",compact=v==Variant.C)
            PlainRow("权限限制","未添加拒绝清单","0 项",R.drawable.ic_lock_keyhole,compact=v==Variant.C)
            Caption("设计演示数据。此版本只演示操作，不改变设备权限。")
        }
        Surface(color=ThemeColors.surface,shadowElevation=3.dp) {Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={if(template)save() else restore=true},enabled=connected,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(template) "预览更改" else "恢复继承")}
            Button(onClick={save()},enabled=connected,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(template) "保存模板" else "保存更改")}
        }}
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},icon={Glyph(R.drawable.ic_check)},title={Text(if(template) "确认模板更改" else "保存应用策略？")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("自启动：${if(startup) "允许" else "禁止"}\n上传限速：${if(cap) "$upload kbit/s" else "关闭"}")
        Text(if(template) "影响 333 个继承应用，保留已有例外。" else "仅对 ${app.title} 建立逐应用规则。")
        Caption("演示操作，不写入真实策略。")
    }},confirmButton={Button(onClick={confirm=false;markDirty(false);notify("演示更改已保存")}) {Text("确认保存")}},dismissButton={TextButton(onClick={confirm=false}) {Text("取消")}})
    if(restore) AlertDialog(onDismissRequest={restore=false},title={Text("恢复继承默认？")},text={Text("将移除 ${app.title} 的演示例外，后续跟随默认模板。")},confirmButton={TextButton(onClick={override=false;cap=false;upload="";startup=false;restore=false;markDirty(false);notify("已恢复继承默认（演示）")}) {Text("恢复继承")}},dismissButton={TextButton(onClick={restore=false}) {Text("取消")}})
    if(conflictDialog) AlertDialog(onDismissRequest={conflictDialog=false},title={Text("配置已被更新")},text={Text("当前版本已过期。重新加载将放弃本页未保存修改，读取最新配置。")},confirmButton={TextButton(onClick={clearConflict();conflictDialog=false;cap=false;upload="";startup=false;markDirty(false);notify("已重新加载演示模板")}) {Text("重新加载")}},dismissButton={TextButton(onClick={conflictDialog=false}) {Text("继续查看")}})
}

@Preview(name="A · 原生简洁",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewA(){DesignTheme{DesignApp(Variant.A)}}
@Preview(name="B · 状态概览",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewB(){DesignTheme{DesignApp(Variant.B)}}
@Preview(name="C · 高效管理",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewC(){DesignTheme{DesignApp(Variant.C)}}
@Preview(name="应用策略",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewDetail(){DesignTheme{DesignApp(Variant.A,"应用策略")}}

@Preview(name="A · 应用",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewAppsA(){DesignTheme{DesignApp(Variant.A,"应用")}}
@Preview(name="B · 应用",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewAppsB(){DesignTheme{DesignApp(Variant.B,"应用")}}
@Preview(name="C · 应用",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewAppsC(){DesignTheme{DesignApp(Variant.C,"应用")}}
@Preview(name="B · 应用策略",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewDetailB(){DesignTheme{DesignApp(Variant.B,"应用策略")}}
@Preview(name="C · 应用策略",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewDetailC(){DesignTheme{DesignApp(Variant.C,"应用策略")}}
@Preview(name="A · 默认模板",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewTemplateA(){DesignTheme{DesignApp(Variant.A,"默认模板")}}
@Preview(name="B · 默认模板",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewTemplateB(){DesignTheme{DesignApp(Variant.B,"默认模板")}}
@Preview(name="C · 默认模板",widthDp=412,heightDp=915,showBackground=true)
@Composable private fun PreviewTemplateC(){DesignTheme{DesignApp(Variant.C,"默认模板")}}
