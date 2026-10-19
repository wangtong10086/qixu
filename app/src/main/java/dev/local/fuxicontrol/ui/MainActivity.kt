package dev.local.fuxicontrol.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.local.fuxicontrol.R
import dev.local.fuxicontrol.core.AppSearch
import dev.local.fuxicontrol.core.Policy
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF6750A4),
                        onPrimary = Color.White,
                        primaryContainer = Color(0xFFEADDFF),
                        onPrimaryContainer = Color(0xFF21005D),
                        surface = Color(0xFFFFFBFE),
                        background = Color(0xFFFFFBFE),
                        surfaceVariant = Color(0xFFF3EDF7),
                        outline = Color(0xFF79747E),
                        onSurface = Color(0xFF1D1B20),
                    )
            ) {
                Controller()
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Glyph(id: Int, description: String? = null) {
    Icon(painterResource(id), description, Modifier.size(24.dp))
}

@Composable
internal fun Entry(
    title: String,
    subtitle: String,
    icon: Int = R.drawable.ic_chevron_right,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    action: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = leading,
        supportingContent = { Text(subtitle) },
        trailingContent = { Glyph(icon) },
        modifier = Modifier.clickable(enabled = enabled, onClick = action).heightIn(min = 72.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
}

private fun keys(value: JSONObject) = value.keys().asSequence().toList()

internal fun modeLabel(value: String) =
    when (value) {
        "enforce" -> "正在执行"
        "observe" -> "仅观察"
        "disabled" -> "已停用"
        else -> "等待连接"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controller() {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val appIcons = remember { AppIcons() }
    var page by rememberSaveable { mutableStateOf("概览") }
    var tab by rememberSaveable { mutableStateOf("概览") }
    var configText by rememberSaveable { mutableStateOf("{}") }
    var status by remember { mutableStateOf(JSONObject()) }
    var apps by remember { mutableStateOf(emptyList<JSONObject>()) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf("all") }
    var policySearch by rememberSaveable { mutableStateOf("") }
    var policyScope by rememberSaveable { mutableStateOf("all") }
    var running by remember { mutableStateOf<JSONObject?>(null) }
    var runningError by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var packageName by rememberSaveable { mutableStateOf("") }
    var appLabel by rememberSaveable { mutableStateOf("") }
    var effectiveText by rememberSaveable { mutableStateOf("{}") }
    var draft by rememberSaveable { mutableStateOf("{}") }
    var original by rememberSaveable { mutableStateOf("{}") }
    var revision by rememberSaveable { mutableStateOf(0L) }
    var report by rememberSaveable { mutableStateOf("{}") }
    var parents by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirm by remember { mutableStateOf("") }
    var leave by remember { mutableStateOf<String?>(null) }
    var modeDialog by remember { mutableStateOf(false) }
    val config = JSONObject(configText)
    val edits = page in listOf("应用策略", "默认模板", "自动化", "推送")
    val dirty = edits && draft != original
    val writable = connected && !busy && !conflict
    fun request(command: JSONObject, result: (JSONObject) -> Unit = {}) {
        scope.launch {
            busy = true
            try {
                val response = withContext(Dispatchers.IO) { Wire.call(command) }
                connected = true
                error = ""
                result(response)
            } catch (e: Wire.CommandException) {
                error = e.message ?: "服务拒绝操作"
                if (error.contains("Configuration changed")) conflict = true
            } catch (e: java.io.IOException) {
                error = e.message ?: "连接失败"
                connected = false
            } catch (e: org.json.JSONException) {
                error = "配置格式错误：${e.message}"
            } finally {
                busy = false
            }
        }
    }
    fun read(command: String, result: (JSONObject) -> Unit) =
        request(JSONObject().put("command", command), result)
    fun refresh() {
        scope.launch {
            busy = true
            try {
                val snapshot =
                    withContext(Dispatchers.IO) {
                        Triple(
                            Wire.call(JSONObject().put("command", "status")),
                            Wire.call(JSONObject().put("command", "config.get")),
                            Wire.call(JSONObject().put("command", "apps.index")),
                        )
                    }
                status = snapshot.first
                configText = snapshot.second.toString()
                val list = snapshot.third.getJSONArray("apps")
                apps =
                    (0 until list.length())
                        .map { list.getJSONObject(it) }
                        .sortedBy { it.getString("label") }
                connected = true
                error = ""
            } catch (e: java.io.IOException) {
                connected = false
                error = e.message ?: "连接失败"
            } finally {
                busy = false
            }
        }
    }
    fun showPage(destination: String) {
        if (destination == page) return
        if (destination in listOf("概览", "应用", "策略", "设置")) {
            parents = emptyList()
            tab = destination
        } else if (parents.lastOrNull() == destination) {
            parents = parents.dropLast(1)
        } else {
            parents = parents + page
        }
        page = destination
    }
    fun openEditor(destination: String) {
        if (destination == "应用策略")
            read("config.get") {
                configText = it.toString()
                revision = it.getLong("revision")
                draft =
                    (it.getJSONObject("apps").optJSONObject(packageName) ?: JSONObject()).toString()
                original = draft
                val inherited = JSONObject(it.toString())
                inherited.getJSONObject("apps").remove(packageName)
                effectiveText =
                    Policy.effective(
                            inherited,
                            packageName,
                            apps
                                .first { app -> app.getString("package") == packageName }
                                .getBoolean("protected"),
                        )
                        .toString()
                showPage(destination)
                conflict = false
            }
        else
            read("config.get") {
                configText = it.toString()
                revision = it.getLong("revision")
                draft =
                    when (destination) {
                        "默认模板" -> it.getJSONObject("defaults").toString()
                        "自动化" -> it.getJSONArray("rules").toString(2)
                        "推送" -> it.getJSONObject("pushProxy").toString(2)
                        else -> it.toString(2)
                    }
                original = draft
                effectiveText = if (destination == "默认模板") draft else "{}"
                showPage(destination)
                conflict = false
            }
    }
    fun navigate(destination: String) {
        if (dirty) leave = destination else showPage(destination)
    }
    fun save(reset: Boolean = false, dryRun: Boolean = false) {
        try {
            val command = JSONObject().put("expectedRevision", revision).put("dryRun", dryRun)
            when (page) {
                "应用策略" -> {
                    command
                        .put("command", if (reset) "app.inherit" else "app.replace")
                        .put("package", packageName)
                    if (!reset) {
                        command.put("patch", normalizedPolicy(draft))
                    }
                }
                "默认模板" -> {
                    command.put("command", "defaults.patch").put("patch", normalizedPolicy(draft))
                }
                else -> {
                    val next =
                        JSONObject(configText).apply {
                            if (page == "自动化") put("rules", normalizedRules(draft))
                            else put("pushProxy", JSONObject(draft))
                        }
                    Policy.validate(next)
                    command.put("command", "config.replace").put("config", next)
                }
            }
            confirm = ""
            request(command) {
                if (dryRun) {
                    scope.launch { snack.showSnackbar("校验通过，尚未保存") }
                } else {
                    configText = it.getJSONObject("config").toString()
                    revision = it.getJSONObject("config").getLong("revision")
                    original = if (reset) "{}" else draft
                    draft = original
                    confirm = ""
                    scope.launch { snack.showSnackbar("已保存") }
                    if (page == "应用策略") openEditor(page) else refresh()
                }
            }
        } catch (e: IllegalArgumentException) {
            error = "输入无效：${e.message}"
        } catch (e: org.json.JSONException) {
            error = "配置格式错误：${e.message}"
        }
    }
    LaunchedEffect(resumed, page) { if (resumed) refresh() }
    LaunchedEffect(resumed, connected) {
        if (resumed && !connected) {
            while (true) {
                delay(5000)
                if (!busy) refresh()
            }
        }
    }
    LaunchedEffect(resumed, page) {
        if (resumed && page in listOf("概览", "应用", "应用例外", "策略")) {
            while (true) {
                try {
                    running =
                        withContext(Dispatchers.IO) {
                            Wire.call(JSONObject().put("command", "apps.running"))
                        }
                    runningError = ""
                } catch (e: Wire.CommandException) {
                    runningError = e.message ?: "服务拒绝操作"
                } catch (e: java.io.IOException) {
                    connected = false
                    runningError = e.message ?: "本地服务连接失败"
                }
                delay(5000)
            }
        }
    }
    BackHandler(page != "概览") { navigate(parentDestination(page, tab, parents)) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (page == "应用策略")
                            apps
                                .firstOrNull { it.getString("package") == packageName }
                                ?.let { ApplicationIcon(it, appIcons) }
                        Column {
                            Text(
                                if (page == "概览") "栖序" else if (page == "应用策略") appLabel else page,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (page == "概览" || page == "应用策略")
                                Text(
                                    if (page == "概览") "当前策略与运行状态" else packageName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                    }
                },
                navigationIcon = {
                    if (page != tab)
                        IconButton(onClick = { navigate(parentDestination(page, tab, parents)) }) {
                            Glyph(R.drawable.ic_arrow_left, "返回")
                        }
                },
                actions = {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            val command =
                                when (page) {
                                    "系统能力" -> "capabilities"
                                    "最近事件" -> "audit"
                                    "网络统计" -> "network.stats"
                                    else -> null
                                }
                            if (command != null) read(command) { report = it.toString() }
                            else refresh()
                        },
                    ) {
                        Glyph(R.drawable.ic_activity, "刷新连接")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (edits)
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (page == "应用策略")
                                OutlinedButton(
                                    enabled = writable,
                                    onClick = { confirm = "inherit" },
                                ) {
                                    Text("恢复继承")
                                }
                            else
                                OutlinedButton(
                                    enabled = writable,
                                    onClick = { save(dryRun = true) },
                                ) {
                                    Text("校验")
                                }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = writable && dirty,
                                onClick = {
                                    val issue =
                                        if (page in listOf("应用策略", "默认模板")) policyError(draft)
                                        else if (page == "自动化") {
                                            try {
                                                normalizedRules(draft)
                                                null
                                            } catch (e: IllegalArgumentException) {
                                                e.message
                                            }
                                        } else null
                                    if (issue != null) error = "输入无效：$issue" else confirm = "save"
                                },
                            ) {
                                Text("保存更改")
                            }
                        }
                    }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    listOf(
                            "概览" to R.drawable.ic_layout_dashboard,
                            "应用" to R.drawable.ic_grid_2x2,
                            "策略" to R.drawable.ic_sliders_horizontal,
                            "设置" to R.drawable.ic_settings_2,
                        )
                        .forEach { (name, icon) ->
                            NavigationBarItem(
                                selected = tab == name,
                                onClick = { navigate(name) },
                                icon = { Glyph(icon) },
                                label = { Text(name) },
                            )
                        }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty())
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            if (conflict) "配置已被其他客户端更新，请重新加载后编辑。" else error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = { if (conflict) openEditor(page) else refresh() },
                        ) {
                            Text(if (conflict) "重新加载" else "重新连接")
                        }
                    }
                }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            ) {
                when (page) {
                    "概览" -> {
                        item {
                            SectionTitle("管理服务")
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Glyph(
                                        if (connected) R.drawable.ic_shield_check
                                        else R.drawable.ic_activity
                                    )
                                    Text(
                                        if (connected) modeLabel(status.optString("mode"))
                                        else "本地服务未连接",
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                    Text(
                                        if (connected) "已连接 · ${dev.local.fuxicontrol.core.Profiles.name(config)}"
                                        else "请检查本地管理服务；无需互联网连接",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    OutlinedButton(
                                        enabled = writable,
                                        onClick = { modeDialog = true },
                                    ) {
                                        Text("切换策略模式")
                                    }
                                    OutlinedButton(enabled = writable, onClick = { navigate("配置方案") }) {
                                        Text("切换配置方案")
                                    }
                                }
                            }
                        }
                        item {
                            RunningSummary(running, runningError) {
                                filter = it
                                navigate("应用")
                            }
                        }
                        item {
                            val hooks = status.optJSONObject("hooks") ?: JSONObject()
                            SectionTitle("当前状态")
                            Entry(
                                "系统检查",
                                "${keys(hooks).count {hooks.optBoolean(it)}} / ${hooks.length()} 项已就绪",
                                R.drawable.ic_check,
                            ) {
                                read("capabilities") {
                                    report = it.toString(2)
                                    showPage("系统能力")
                                }
                            }
                            Entry(
                                "${apps.size} 个应用",
                                "${config.optJSONObject("apps")?.length() ?: 0} 个逐应用例外",
                            ) {
                                navigate("应用")
                            }
                            Entry("默认模板", "普通应用继承默认，关键服务保留保护") { openEditor("默认模板") }
                            if (status.optString("lastError").isNotEmpty())
                                Text(
                                    "执行异常：${status.getString("lastError")}",
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    }
                    "应用",
                    "应用例外" -> {
                        item {
                            DiscoverySearch(
                                search,
                                { search = it },
                                searchScope,
                                { searchScope = it },
                            )
                            if (page == "应用" && search.isBlank())
                                RunningSummary(running, runningError) { filter = it }
                            else if (runningError.isNotEmpty())
                                Text(
                                    "运行状态为上次快照：$runningError",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            if (page == "应用")
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(
                                            "全部",
                                            "运行中",
                                            "前台",
                                            "可见",
                                            "后台",
                                            "已缓存",
                                            "继承默认",
                                            "有例外",
                                            "关键服务",
                                        )
                                        .forEach { name ->
                                            FilterChip(
                                                filter == name,
                                                { filter = name },
                                                label = { Text(name) },
                                            )
                                        }
                                }
                        }
                        val overrides = config.optJSONObject("apps") ?: JSONObject()
                        val visible =
                            apps.filter { app ->
                                val name = app.getString("package")
                                AppSearch.matches(
                                    app,
                                    app.getJSONObject("effective"),
                                    search,
                                    searchScope,
                                ) &&
                                    when (if (page == "应用例外") "有例外" else filter) {
                                        "运行中" ->
                                            running
                                                ?.getJSONObject("apps")
                                                ?.optJSONObject(name)
                                                ?.getString("state")
                                                ?.let { it != "cached" } == true
                                        "前台",
                                        "可见",
                                        "后台",
                                        "已缓存" ->
                                            running
                                                ?.getJSONObject("apps")
                                                ?.optJSONObject(name)
                                                ?.getString("state") ==
                                                processLabels.entries
                                                    .first {
                                                        it.value ==
                                                            if (filter == "已缓存") "缓存" else filter
                                                    }
                                                    .key
                                        "有例外" -> overrides.has(name)
                                        "继承默认" -> !overrides.has(name)
                                        "关键服务" -> app.getBoolean("protected")
                                        else -> true
                                    }
                            }
                        item {
                            Text(
                                "${visible.size} 个应用",
                                Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        items(visible, key = { it.getString("package") }) { app ->
                            val name = app.getString("package")
                            val p = app.getJSONObject("effective")
                            Entry(
                                app.getString("label"),
                                (if (overrides.has(name)) "逐应用例外"
                                else if (app.getBoolean("protected")) "关键服务保护" else "继承默认") +
                                    " · " +
                                    (if (p.getString("autostart") == "deny") "禁自启" else "允许自启") +
                                    "\n" +
                                    name +
                                    "\n" +
                                    processDescription(
                                        running?.getJSONObject("apps")?.optJSONObject(name),
                                        running != null,
                                    ) +
                                    permissionEvidence(app, search, searchScope),
                                enabled = writable,
                                leading = { ApplicationIcon(app, appIcons) },
                            ) {
                                packageName = name
                                appLabel = app.getString("label")
                                openEditor("应用策略")
                            }
                        }
                        if (visible.isEmpty())
                            item { Text("没有匹配的应用", Modifier.padding(vertical = 32.dp)) }
                    }
                    "应用策略",
                    "默认模板" ->
                        item {
                            Text(
                                if (page == "默认模板") "默认值实时作用于继承字段；已有逐项例外及关键服务保护优先。"
                                else "只保存修改过的字段，其余字段继续继承默认或关键服务保护。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val inherited =
                                if (page == "应用策略" && apps.isNotEmpty()) {
                                    val baseline = JSONObject(configText)
                                    val protection = JSONObject()
                                    if (JSONObject(draft).has("protected"))
                                        protection.put(
                                            "protected",
                                            JSONObject(draft).getBoolean("protected"),
                                        )
                                    baseline.getJSONObject("apps").put(packageName, protection)
                                    Policy.effective(
                                        baseline,
                                        packageName,
                                        apps
                                            .first { it.getString("package") == packageName }
                                            .getBoolean("protected"),
                                    )
                                } else JSONObject(effectiveText)
                            key(page, packageName, revision) {
                                PolicyForm(draft, inherited, page == "默认模板", writable) {
                                    draft = it
                                }
                            }
                        }
                    "策略" -> {
                        item {
                            DiscoverySearch(
                                policySearch,
                                { policySearch = it },
                                policyScope,
                                { policyScope = it },
                            )
                            SectionTitle("应用规则")
                            if (AppSearch.textMatches("默认模板 自启动 后台 资源 CPU 内存 网络 权限", policySearch))
                                Entry("默认模板", "自启动、后台、资源、网络与权限") { openEditor("默认模板") }
                            if (AppSearch.textMatches("自动化 规则", policySearch))
                                Entry("自动化", "${config.optJSONArray("rules")?.length() ?: 0} 条规则") {
                                    openEditor("自动化")
                                }
                            if (AppSearch.textMatches("推送", policySearch))
                                Entry(
                                    "推送",
                                    "${config.optJSONObject("pushProxy")?.length() ?: 0} 个应用",
                                ) {
                                    openEditor("推送")
                                }
                        }
                        if (policySearch.isNotBlank()) {
                            val matches =
                                apps.filter {
                                    AppSearch.matches(
                                        it,
                                        it.getJSONObject("effective"),
                                        policySearch,
                                        policyScope,
                                    )
                                }
                            item { SectionTitle("匹配应用 · ${matches.size}") }
                            items(matches, key = { it.getString("package") }) { app ->
                                val name = app.getString("package")
                                Entry(
                                    app.getString("label"),
                                    (if (config.getJSONObject("apps").has(name)) "逐应用例外"
                                    else "继承默认或关键服务保护") +
                                        "\n" +
                                        name +
                                        permissionEvidence(app, policySearch, policyScope),
                                    enabled = writable,
                                    leading = { ApplicationIcon(app, appIcons) },
                                ) {
                                    packageName = name
                                    appLabel = app.getString("label")
                                    openEditor("应用策略")
                                }
                            }
                            if (matches.isEmpty()) item { Text("没有匹配的应用") }
                        } else
                            item {
                                SectionTitle("规则优先级")
                                Text("逐应用例外 → 关键服务保护 → 默认模板。更新应用保留例外，新装普通应用继承默认。")
                            }
                    }
                    "设置" ->
                        item {
                            SectionTitle("诊断与高级")
                            listOf(
                                    "系统能力" to "capabilities",
                                    "最近事件" to "audit",
                                    "网络统计" to "network.stats",
                                )
                                .forEach { (label, cmd) ->
                                    Entry(
                                        label,
                                        when (cmd) {
                                            "capabilities" -> "资源控制与功能状态"
                                            "audit" -> "近期策略执行记录"
                                            else -> "上传、下载与丢包"
                                        },
                                        enabled = !busy,
                                    ) {
                                        read(cmd) {
                                            report = it.toString(2)
                                            showPage(label)
                                        }
                                    }
                                }
                            Entry("CLI", "命令参考") { showPage("CLI") }
                            Entry("配置管理", "默认模板、应用例外、自动化与推送", enabled = !busy) {
                                read("config.get") {
                                    configText = it.toString()
                                    showPage("配置管理")
                                }
                            }
                            Entry("开源许可", "Lucide 图标 · ISC") { showPage("开源许可") }
                        }
                    "自动化",
                    "推送" ->
                        item {
                            RuleForms(draft, page == "推送", apps, appIcons, writable) { draft = it }
                        }
                    "配置方案" -> item {
                        ProfileView(config, writable) { command ->
                            request(command.put("expectedRevision", config.getLong("revision"))) {
                                configText = it.getJSONObject("config").toString()
                                refresh()
                            }
                        }
                    }
                    "配置管理" ->
                        item {
                            Entry("配置方案", dev.local.fuxicontrol.core.Profiles.name(config), enabled = writable) { navigate("配置方案") }
                            SectionTitle("运行模式")
                            Entry(
                                modeLabel(config.getString("mode")),
                                "选择执行、观察或停用",
                                enabled = writable,
                            ) {
                                modeDialog = true
                            }
                            SectionTitle("配置项目")
                            Entry("默认模板", "修改普通应用继承的默认值", enabled = writable) { openEditor("默认模板") }
                            Entry(
                                "应用例外",
                                "${config.getJSONObject("apps").length()} 个应用",
                                enabled = writable,
                            ) {
                                search = ""
                                showPage("应用例外")
                            }
                            Entry(
                                "自动化",
                                "${config.getJSONArray("rules").length()} 条规则",
                                enabled = writable,
                            ) {
                                openEditor("自动化")
                            }
                            Entry(
                                "推送",
                                "${config.optJSONObject("pushProxy")?.length() ?: 0} 个应用",
                                enabled = writable,
                            ) {
                                openEditor("推送")
                            }
                        }
                    "系统能力" -> item { CapabilityView(JSONObject(report)) }
                    "最近事件" -> {
                        val records = JSONObject(report).getJSONArray("records")
                        item { SectionTitle("最近 ${records.length()} 条事件") }
                        if (records.length() == 0) item { Text("暂无事件") }
                        items(
                            (0 until records.length()).reversed().map { records.getJSONObject(it) }
                        ) { record ->
                            AuditRecordView(record, apps, appIcons)
                        }
                    }
                    "网络统计" -> {
                        val data = JSONObject(report)
                        item {
                            SectionTitle("受控流量")
                            Text(if (data.getBoolean("loaded")) "限速程序已加载" else "限速程序未加载，暂无计数")
                        }
                        if (data.getBoolean("loaded")) {
                            val counters = data.getJSONArray("counters")
                            if (counters.length() == 0)
                                item { Text("暂无受控流量记录", Modifier.padding(vertical = 16.dp)) }
                            items(
                                (0 until counters.length())
                                    .map { counters.getJSONObject(it) }
                                    .sortedBy { it.getInt("uid") }
                            ) { counter ->
                                NetworkCounterView(counter, apps, appIcons)
                            }
                        }
                    }
                    "CLI" ->
                        item {
                            SectionTitle("本机或 adb root")
                            Text(
                                "/data/adb/fuxicontrol/fuxictl status\n\nfuxictl apps\nfuxictl explain PACKAGE\nfuxictl defaults show\nfuxictl config export FILE\nfuxictl help",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    "开源许可" ->
                        item {
                            Text(
                                "Lucide icons · ISC License\nCopyright (c) Lucide Contributors\n\nPermission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.\n\nTHE SOFTWARE IS PROVIDED AS IS AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE."
                            )
                        }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (leave != null)
        AlertDialog(
            onDismissRequest = { leave = null },
            title = { Text("放弃未保存的更改？") },
            text = { Text("当前更改尚未写入服务。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft = original
                        showPage(leave!!)
                        leave = null
                        conflict = false
                        error = ""
                    }
                ) {
                    Text("放弃更改")
                }
            },
            dismissButton = { TextButton(onClick = { leave = null }) { Text("继续编辑") } },
        )
    val affected =
        if (confirm == "save" && page == "默认模板") {
            val next = JSONObject(configText).put("defaults", normalizedPolicy(draft))
            apps.count { app ->
                val name = app.getString("package")
                val protectedRole = app.getBoolean("protected")
                val before = Policy.effective(config, name, protectedRole)
                val after = Policy.effective(next, name, protectedRole)
                policyFields.any { before.opt(it.key)?.toString() != after.opt(it.key)?.toString() }
            }
        } else 0
    if (confirm.isNotEmpty())
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text(if (confirm == "inherit") "恢复全部继承？" else "确认保存更改") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (confirm == "inherit") Text("移除此应用的全部例外，重新遵循默认模板和角色保护。")
                    else {
                        Text(
                            if (page == "默认模板") "当前安装应用中，$affected 个应用的有效策略将变化；逐项例外继续优先。"
                            else if (page == "应用策略") "仅修改 $appLabel 的策略。" else "将保存当前配置，服务会重新应用策略。"
                        )
                        if (page in listOf("默认模板", "应用策略")) {
                            val before = JSONObject(original)
                            val after = JSONObject(draft)
                            policyFields
                                .filter {
                                    before.opt(it.key)?.toString() != after.opt(it.key)?.toString()
                                }
                                .forEach {
                                    Text(
                                        "${it.title}：${policyValueLabel(it,before.opt(it.key))} → ${policyValueLabel(it,after.opt(it.key))}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = writable, onClick = { save(reset = confirm == "inherit") }) {
                    Text("确认保存")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("取消") } },
        )
    if (modeDialog)
        AlertDialog(
            onDismissRequest = { modeDialog = false },
            title = { Text("策略模式") },
            text = {
                Column {
                    Text("执行会施加限制；观察仅记录；停用会恢复受管设置。")
                    listOf("enforce", "observe", "disabled").forEach { mode ->
                        TextButton(
                            enabled = writable,
                            onClick = {
                                modeDialog = false
                                request(
                                    JSONObject()
                                        .put("command", "mode.set")
                                        .put("mode", mode)
                                        .put("expectedRevision", config.getLong("revision"))
                                ) {
                                    refresh()
                                }
                            },
                        ) {
                            Text(modeLabel(mode))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modeDialog = false }) { Text("取消") } },
        )
}
