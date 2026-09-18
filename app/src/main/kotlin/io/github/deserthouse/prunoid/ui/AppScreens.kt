package io.github.deserthouse.prunoid.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// M3E 视觉（"冷静的审计台"）+ 审查清单落地：
// 应用状态可见 / 手动重扫 / 系统 app 防误操作 / 逐 SDK 勾选 / 量化确认 / busy 态 / 恢复双击确认。
// 包名/组件名/命令一律等宽；浏览=平铺，聚焦=卡片。

private fun safetyIcon(s: Safety): ImageVectorAlias = when (s) {
    Safety.SAFE -> Icons.Outlined.Check
    Safety.CAUTION -> Icons.Outlined.WarningAmber
    Safety.RISKY -> Icons.Outlined.ErrorOutline
    Safety.UNKNOWN -> Icons.Outlined.HelpOutline
}

private typealias ImageVectorAlias = androidx.compose.ui.graphics.vector.ImageVector

/** 分类枚举 → 中文（schema 语言不穿透到 UI） */
fun categoryLabel(c: String): String = when (c) {
    "ads" -> "广告"
    "push" -> "推送"
    "analytics" -> "统计"
    "quality" -> "质量"
    "social_or_pay" -> "社媒/支付"
    "maps" -> "地图"
    "infra" -> "基础组件"
    "security" -> "安全"
    "framework" -> "框架"
    else -> "其他"
}

/** SDK monogram 头像（LibChecker tonal avatar 语义）：规则 id 哈希取色，公司名/SDK 名首字母 */
private val MONOGRAM_COLORS = listOf(
    Color(0xFFB3E5FC) to Color(0xFF01579B),
    Color(0xFFFFCDD2) to Color(0xFF880E4F),
    Color(0xFFC8E6C9) to Color(0xFF1B5E20),
    Color(0xFFFFE0B2) to Color(0xFF7A4100),
    Color(0xFFD1C4E9) to Color(0xFF4527A0),
    Color(0xFFB2DFDB) to Color(0xFF004D40),
    Color(0xFFF8BBD0) to Color(0xFF880E4F),
    Color(0xFFCFD8DC) to Color(0xFF37474F)
)

/** 规则 id → LibChecker-Rules-Bundle 矢量图标名（Apache-2.0，assets/icons/lib_icons.json，构建期生成，覆盖主流 SDK） */
@Volatile private var libIconsCache: Map<String, String>? = null

private fun loadLibIcons(ctx: android.content.Context): Map<String, String> {
    libIconsCache?.let { return it }
    val m = runCatching {
        val json = ctx.assets.open("icons/lib_icons.json").bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(json)
    }.getOrDefault(emptyMap())
    libIconsCache = m
    return m
}

@Composable
fun SdkMonogram(ruleId: String, name: String, modifier: Modifier = Modifier, iconUrl: String? = null) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = MONOGRAM_COLORS[ruleId.hashCode().let { if (it < 0) -it else it } % MONOGRAM_COLORS.size]
    val bgC = if (dark) fg.copy(alpha = 0.25f) else bg
    val fgC = if (dark) MaterialTheme.colorScheme.onSurface else fg
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val iconName = remember { loadLibIcons(ctx)[ruleId] }
    val iconRes = iconName?.let {
        remember(it) {
            runCatching {
                val id = ctx.resources.getIdentifier(it, "drawable", ctx.packageName)
                if (id != 0) id else null
            }.getOrNull()
        }
    }
    Box(
        modifier
            .size(32.dp)
            .background(bgC, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) {
        when {
            // 贡献者提供的品牌图标（iconUrl，懒加载）优先
            !iconUrl.isNullOrBlank() -> coil.compose.AsyncImage(
                model = iconUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            iconRes != null -> Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            else -> Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = fgC
            )
        }
    }
}

/** 四级安全徽标：图标 + 文字（色不单独表意） */
@Composable
fun SafetyBadge(s: Safety, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val c = safetyColors(s, dark)
    Surface(
        color = c.container,
        contentColor = c.onContainer,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(safetyIcon(s), contentDescription = null, modifier = Modifier.size(13.dp))
            Text(safetyLabel(s), style = MaterialTheme.typography.labelSmall, fontSize = 12.sp)
        }
    }
}

@Composable
fun rememberSnackbar(): SnackbarHostState = remember { SnackbarHostState() }

@Composable
fun SnackbarEffect(snackbar: SnackbarHostState, message: String?) {
    LaunchedEffect(message) {
        message?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it, withDismissAction = true) }
    }
}

private fun fmtTime(epochMs: Long): String = if (epochMs <= 0) "" else
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

// ─────────────────────────── 列表屏 ───────────────────────────

@Composable
fun AppListScreen(vm: AppViewModel, onOpen: (ScannedApp) -> Unit, onOpenSettings: () -> Unit, onOpenLibrary: () -> Unit) {
    val st by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val snackbar = rememberSnackbar()
    var showSubscribe by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var subMsg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 订阅点语义：任一源成功拉取过才亮（sources 注册表默认恒含官方源，不能作为判据）
    val subscribed = st.sources.any { it.lastFetched.isNotBlank() }

    SnackbarEffect(snackbar, st.message)
    SnackbarEffect(snackbar, subMsg)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prunoid", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        // 订阅状态点：实心=已订阅，描边=内置快照
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (subscribed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50)
                                )
                                .semantics {
                                    contentDescription = if (subscribed) "已订阅规则源" else "使用内置快照"
                                }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.rescan() }, enabled = !st.scanning) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
                    }
                    IconButton(onClick = { showSubscribe = true }) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "规则订阅源")
                    }
                    IconButton(onClick = { showRecovery = true }) {
                        Icon(Icons.Outlined.HealthAndSafety, contentDescription = "备份与应急恢复")
                    }
                    IconButton(onClick = onOpenLibrary) {
                        Icon(Icons.Outlined.Apps, contentDescription = "SDK 库")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            val visible = st.apps.filter { st.showSystem || !it.isSystem }
            val hits = visible.count { it.matchedSdks.isNotEmpty() }
            Surface(tonalElevation = 2.dp) {
                Text(
                    if (st.apps.isEmpty()) "正在建立索引…"
                    else "${visible.size} 个 app · $hits 个命中 SDK",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!st.rootGranted) {
                Surface(
                    color = if (dark) WarnContainerDark else WarnContainerLight,
                    contentColor = if (dark) WarnOnContainerDark else WarnOnContainerLight
                ) {
                    Text(
                        "未取得 root：扫描可用，禁用操作不可用",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索 app / 包名 / SDK") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = st.showSystem,
                    onClick = { vm.toggleShowSystem() },
                    label = { Text("含系统 app（只读）") }
                )
                FilterChip(
                    selected = st.hitsOnly,
                    onClick = { vm.toggleHitsOnly() },
                    label = { Text("仅看命中") }
                )
            }
            if (st.scanning) {
                LinearWavyProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            val apps = remember(st.apps, st.showSystem, st.hitsOnly, query) {
                st.apps
                    .filter { st.showSystem || !it.isSystem }
                    .filter { !st.hitsOnly || it.matchedSdks.isNotEmpty() }
                    .filter {
                        query.isBlank() || it.label.contains(query, true) ||
                            it.packageName.contains(query, true) ||
                            it.matchedSdks.any { s -> s.name.contains(query, true) }
                    }
            }
            if (apps.isEmpty() && !st.scanning) {
                Column(
                    Modifier.fillMaxSize().padding(bottom = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (query.isBlank()) "没有可显示的 app" else "无匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        val icon = st.icons[app.packageName]
                        val appliedEntry = st.applied[app.packageName]
                        // 命中集最严重风险级 → 徽标色点（RISKY > UNKNOWN > CAUTION > SAFE）
                        val worst = listOf(Safety.RISKY, Safety.UNKNOWN, Safety.CAUTION, Safety.SAFE)
                            .firstOrNull { w -> app.matchedSdks.any { it.safety == w } }
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.label.ifEmpty { app.packageName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (app.isSystem) {
                                        Spacer(Modifier.width(6.dp))
                                        // 白名单命中 = 框架/核心层（硬拦截），其余系统 app = 普通系统层
                                        val framework = DisableEngine.isForbidden(app.packageName)
                                        Surface(
                                            color = if (framework) MaterialTheme.colorScheme.errorContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (framework) MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                if (framework) "框架" else "系统",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (appliedEntry != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = "已应用规则",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    app.packageName,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                coil.compose.AsyncImage(
                                    model = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp)
                                )
                            },
                            trailingContent = {
                                if (app.matchedSdks.isNotEmpty()) {
                                    SuggestionChip(
                                        onClick = { onOpen(app) },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (worst != null) Box(
                                                    Modifier
                                                        .size(8.dp)
                                                        .background(safetyColors(worst, dark).container, RoundedCornerShape(50))
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("${app.matchedSdks.size} SDK")
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .animateItem()
                                .clickable { onOpen(app) }
                        )
                    }
                }
            }
        }
    }

    if (showSubscribe) {
        SubscribeDialog(
            initial = "",
            onDismiss = { showSubscribe = false },
            onConfirm = { url ->
                showSubscribe = false
                vm.addSource("", url) { subMsg = it }
            }
        )
    }
    if (showRecovery) {
        RecoveryDialog(
            vm = vm,
            onMessage = { subMsg = it },
            onDismiss = { showRecovery = false }
        )
    }
}

@Composable
fun SubscribeDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则订阅源") },
        text = {
            Column {
                Text("格式与内置快照一致（schemaVersion + sdks），支持自建源")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") }
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        url = "https://raw.githubusercontent.com/deserthouse/Prunoid-Rules/main/rules/snapshot.json"
                    }
                ) { Text("填入官方源") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(url.trim()) },
                enabled = url.startsWith("http")
            ) { Text("订阅") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun RecoveryDialog(vm: AppViewModel, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val backups = remember { vm.listBackups() }
    val busy = vm.state.collectAsState().value.busy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备份与应急恢复") },
        text = {
            Column {
                if (backups.isEmpty()) {
                    Text("暂无备份（应用规则时会自动创建）")
                } else {
                    Text("恢复点（新→旧）：", style = MaterialTheme.typography.bodySmall)
                    backups.take(10).forEach { path ->
                        val name = path.substringAfterLast('/')
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == path,
                                onClick = { selected = path }
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "adb shell am broadcast -a io.github.deserthouse.prunoid.action.CLEAR_IFW --ez confirm true",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row {
                // 清除全部 IFW：最高危级 → 倒计时锁定（Thanox 式）
                CountdownConfirmTextButton(
                    label = "清除全部 IFW",
                    armedLabel = "确认清除全部",
                    enabled = !busy,
                    onConfirm = { vm.clearAllIfw { onMessage(it) } }
                )
                Spacer(Modifier.width(4.dp))
                // 恢复所选：倒计时锁定
                CountdownConfirmTextButton(
                    label = "恢复所选",
                    armedLabel = "确认恢复所选",
                    enabled = selected != null && !busy,
                    onConfirm = {
                        selected?.let { p ->
                            vm.restoreBackup(p) { onMessage(it) }
                            onDismiss()
                        }
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ─────────────────────────── 详情屏 ───────────────────────────

@Composable
fun AppDetailScreen(app: ScannedApp, vm: AppViewModel, onBack: () -> Unit) {
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    val st by vm.state.collectAsState()
    val pm = LocalContext.current.packageManager
    // 逐 SDK 勾选：默认勾选 SAFE/CAUTION（RISKY/UNKNOWN 需显式加选）
    val defaultSelected = remember(app.packageName) {
        app.matchedSdks.filter { it.safety == Safety.SAFE || it.safety == Safety.CAUTION }
            .map { it.ruleId }.toSet()
    }
    var selected by remember(app.packageName) { mutableStateOf(defaultSelected) }
    // 分类筛选：null = 全部
    var catFilter by remember(app.packageName) { mutableStateOf<String?>(null) }
    // SDK 档案卡弹层（Blocker/LibChecker 模式）：点卡片打开
    var sheetFor by remember { mutableStateOf<SdkHit?>(null) }
    var detailTab by remember { mutableStateOf(0) }   // 0=SDK 视图 1=组件视图
    var compTypeFilter by remember { mutableStateOf<String?>(null) }  // 组件视图类型过滤
    val appliedEntry = st.applied[app.packageName]
    // 安全分层：白名单命中（框架/核心）= 硬拦截；其余系统 app = 警告后可操作
    val framework = DisableEngine.isForbidden(app.packageName)
    val systemWarn = app.isSystem && !framework

    val versionName = remember(app.packageName) {
        runCatching {
            pm.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()
    }.let { if (it.isNullOrBlank()) "无版本号" else it }

    SnackbarEffect(snackbar, msg)

    // 分类筛选后的可见 SDK 与勾选摘要
    val visibleSdks = remember(app.matchedSdks, catFilter) {
        if (catFilter == null) app.matchedSdks
        else app.matchedSdks.filter { it.category == catFilter }
    }
    val categories = remember(app.matchedSdks) {
        app.matchedSdks.map { it.category }.distinct()
    }

    // 量化确认摘要（以当前勾选与引擎为准）
    val selHits = app.matchedSdks.filter { it.ruleId in selected }
    val selComponents = selHits.flatMap { it.matchedComponents }
    val excludedRisky = app.matchedSdks.count {
        it.safety == Safety.RISKY && it.ruleId !in selected
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(app.label.ifEmpty { app.packageName }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth()) {
                    if (framework) {
                        // 框架/核心层：安全层硬拦截，永不可操作
                        Text(
                            "框架/系统核心组件：安全层白名单硬拦截，不可修改（防 bootloop 设计）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else if (systemWarn) {
                        Text(
                            "系统应用：修改可能影响系统功能，操作将被要求二次风险确认",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSystemInDarkTheme()) WarnContainerLight else WarnOnContainerLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showApplyConfirm = true },
                            enabled = !framework && !st.busy && selected.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (st.busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (selected.isNotEmpty() && selected != defaultSelected) "应用所选 ${selected.size} 项" else "应用规则")
                        }
                        OutlinedButton(
                            onClick = { vm.restoreApp(app) { msg = it } },
                            enabled = !framework && !st.busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("恢复") }
                    }
                }
            }
        }
    ) { padding ->
        val compRows = remember(app, compTypeFilter) {
            app.matchedSdks.flatMap { sdk ->
                sdk.matchedComponents.mapNotNull { cn ->
                    val t = sdk.componentTypes[cn]
                        ?: when {
                            cn.endsWith("Activity") -> "activity"
                            cn.endsWith("Service") -> "service"
                            cn.endsWith("Receiver") -> "receiver"
                            cn.endsWith("Provider") -> "provider"
                            else -> "other"
                        }
                    if (compTypeFilter == null || t == compTypeFilter) Triple(cn, t, sdk) else null
                }
            }.sortedBy { it.first }
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val icon = st.icons[app.packageName]
                            coil.compose.AsyncImage(
                                model = icon,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp)
                            )
                            Column {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    app.packageName,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "v$versionName · 命中 ${app.matchedSdks.size} SDK · ${app.matchedSdks.sumOf { it.matchedComponents.size }} 组件",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                // 大头部补充行（LibChecker 式）：Target/Min/Size
                                val appInfo = remember(app.packageName) {
                                    runCatching { pm.getApplicationInfo(app.packageName, 0) }.getOrNull()
                                }
                                val apkSizeMb = remember(app.packageName) {
                                    appInfo?.sourceDir?.let {
                                        runCatching { java.io.File(it).length() / 1048576 }.getOrNull()
                                    }
                                }
                                Text(
                                    buildString {
                                        append("Target ${appInfo?.targetSdkVersion ?: "?"} · Min ${appInfo?.minSdkVersion ?: "?"}")
                                        if (apkSizeMb != null) append(" · ${apkSizeMb} MB")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 安全分布 mini-dots + 已应用状态行
                        if (app.matchedSdks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Safety.entries.forEach { s ->
                                    val n = app.matchedSdks.count { it.safety == s }
                                    if (n > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(safetyColors(s, isSystemInDarkTheme()).container, RoundedCornerShape(50))
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        // 已检出 SDK 快捷条（LibChecker 式）：点头像直达档案卡
                        if (app.matchedSdks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                app.matchedSdks.take(10).forEach { hit ->
                                    SdkMonogram(
                                        hit.ruleId, hit.name,
                                        Modifier.clickable { sheetFor = hit }
                                    )
                                }
                                val rest = app.matchedSdks.size - 10
                                if (rest > 0) {
                                    Text(
                                        "+$rest",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        appliedEntry?.let { e ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "已应用 ${e.components.size} 组件（${e.engine}）" +
                                        if (e.at > 0) " · ${fmtTime(e.at)}" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Engine.entries.forEachIndexed { i, e ->
                            SegmentedButton(
                                selected = st.engine == e,
                                onClick = { vm.selectEngine(e) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = i, count = Engine.entries.size
                                )
                            ) { Text(e.label) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (st.engine == Engine.IFW)
                            "IFW：app 无感知、无法自恢复（推荐）"
                        else
                            "pm disable：兼容性好，但 app 可能自行恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 分类筛选：按 category 过滤 + 批量勾选操作
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = detailTab == 0,
                        onClick = { detailTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("SDK 视图") }
                    SegmentedButton(
                        selected = detailTab == 1,
                        onClick = { detailTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("组件视图") }
                }
            }
            if (detailTab == 1) {
                item {
                    // 组件视图类型过滤
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = compTypeFilter == null,
                            onClick = { compTypeFilter = null },
                            label = { Text("全部类型") }
                        )
                        listOf("activity" to "界面", "service" to "服务", "receiver" to "广播", "provider" to "提供器").forEach { (t, l) ->
                            val has = app.matchedSdks.any { sdk ->
                                sdk.matchedComponents.any { cn -> (sdk.componentTypes[cn] ?: "") == t }
                            }
                            if (has) FilterChip(
                                selected = compTypeFilter == t,
                                onClick = { compTypeFilter = if (compTypeFilter == t) null else t },
                                label = { Text(l) }
                            )
                        }
                    }
                }
                items(compRows, key = { it.first + it.third.ruleId }) { (cn, t, sdk) ->
                    ListItem(
                        headlineContent = {
                            Text(cn.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                        },
                        supportingContent = {
                            Text(
                                cn,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    typeLabel(t),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        },
                        trailingContent = {
                            SdkMonogram(sdk.ruleId, sdk.name)
                        }
                    )
                }
            }
            if (detailTab == 0 && categories.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = catFilter == null,
                                onClick = { catFilter = null },
                                label = { Text("全部") }
                            )
                            categories.forEach { c ->
                                FilterChip(
                                    selected = catFilter == c,
                                    onClick = { catFilter = if (catFilter == c) null else c },
                                    label = { Text(categoryLabel(c)) }
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "当前显示 ${visibleSdks.size} 个 SDK",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { selected = selected + visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty()
                            ) { Text("全选筛选结果") }
                            TextButton(
                                onClick = { selected = selected - visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty()
                            ) { Text("全不选") }
                        }
                    }
                }
            }
            if (detailTab == 0) items(visibleSdks, key = { it.ruleId }) { hit ->
                val checked = hit.ruleId in selected
                // 整卡 = 打开 SDK 档案卡弹层；仅左侧复选框独立点选
                Card(
                    onClick = { sheetFor = hit },
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + hit.ruleId else selected - hit.ruleId
                            }
                        )
                        SdkMonogram(hit.ruleId, hit.name, Modifier.padding(end = 8.dp), vm.ruleInfo(hit.ruleId)?.iconUrl)
                        Column(Modifier.weight(1f)) {
                            Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                categoryLabel(hit.category),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SafetyBadge(hit.safety)
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = "详情",
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            // 未识别组件（LibChecker "Unmarked library" 语义）：只读展示，供人审与规则仓 PR
            if (detailTab == 0 && app.unmatched.isNotEmpty()) {
                item {
                    var unmatchedOpen by remember { mutableStateOf(false) }
                    // 动效对齐 Blocker 克制区间（tween 100~200ms，FastOutSlowIn）
                    val rot by animateFloatAsState(
                        targetValue = if (unmatchedOpen) 180f else 0f,
                        animationSpec = tween(150, easing = FastOutSlowInEasing),
                        label = "unmatchedArrow"
                    )
                    val total = app.unmatched.sumOf { it.count }
                    Card(
                        onClick = { unmatchedOpen = !unmatchedOpen },
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize(tween(150, easing = FastOutSlowInEasing))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("未识别组件 $total", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "不在规则库中，暂无法禁用；可提交至规则仓库",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = if (unmatchedOpen) "收起" else "展开",
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .rotate(rot)
                                )
                            }
                            if (unmatchedOpen) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                app.unmatched.forEach { g ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            g.prefix,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (g.suspicious) {
                                                Surface(
                                                    color = safetyColors(Safety.CAUTION, isSystemInDarkTheme()).container,
                                                    contentColor = safetyColors(Safety.CAUTION, isSystemInDarkTheme()).onContainer,
                                                    shape = RoundedCornerShape(50)
                                                ) {
                                                Text(
                                                    "疑似广告/统计",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${g.count}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { hit ->
        SdkArchiveSheet(
            hit = hit,
            app = app,
            vm = vm,
            appliedEntry = appliedEntry,
            checked = hit.ruleId in selected,
            onToggle = { on ->
                selected = if (on) selected + hit.ruleId else selected - hit.ruleId
            },
            onDismiss = { sheetFor = null }
        )
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            icon = {
                if (systemWarn) Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(if (systemWarn) "警告：正在修改系统应用" else "应用规则") },
            text = {
                Column {
                    if (systemWarn) {
                        Text(
                            "「${app.label}」是系统应用。禁用其组件可能导致该应用甚至系统功能异常。" +
                                "如出现问题，可在本应用内恢复，或通过备份/应急通道回滚。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "将禁用 ${selHits.size} 个 SDK 的 ${selComponents.size} 个组件" +
                            (if (excludedRisky > 0) "（已排除 $excludedRisky 个风险项）" else "") +
                            "，引擎 ${st.engine.name}。操作前自动创建备份。"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    vm.applyRules(app, selected) { msg = it }
                }) {
                    Text(
                        if (systemWarn) "我已了解风险，继续" else "应用",
                        color = if (systemWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text("取消") }
            }
        )
    }
}

fun typeLabel(t: String) = when (t) {
    "activity" -> "界面 activity"
    "service" -> "后台 service"
    "receiver" -> "广播 receiver"
    "provider" -> "provider"
    else -> "其他组件"
}

fun confidenceLabel(c: String) = when (c) {
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> c
}

fun safetyLabel(s: Safety) = when (s) {
    Safety.SAFE -> "可安全禁用"
    Safety.CAUTION -> "谨慎禁用"
    Safety.RISKY -> "禁用有风险"
    Safety.UNKNOWN -> "影响未知"
}


/** SDK 档案卡底部弹层（LibChecker 结构 ⊕ Blocker 结构化字段 ⊕ 量化句） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkArchiveSheet(
    hit: SdkHit,
    app: ScannedApp,
    vm: AppViewModel,
    appliedEntry: AppliedRulesStore.AppliedEntry?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val info = remember(hit.ruleId) { vm.ruleInfo(hit.ruleId) }
    // 量化句：N matched / N blocked（blocked = 已应用记录中命中的组件数）
    val blocked = appliedEntry?.components?.count { c -> hit.matchedComponents.any { it == c } } ?: 0
    val groups = remember(hit.ruleId) {
        hit.matchedComponents.groupBy { cn ->
            hit.componentTypes[cn] ?: when {
                cn.endsWith("Activity") -> "activity"
                cn.endsWith("Service") -> "service"
                cn.endsWith("Receiver") -> "receiver"
                cn.endsWith("Provider") -> "provider"
                else -> "other"
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SdkMonogram(hit.ruleId, hit.name, Modifier.size(56.dp), vm.ruleInfo(hit.ruleId)?.iconUrl)
            Spacer(Modifier.height(8.dp))
            Text(hit.name, style = MaterialTheme.typography.titleLarge)
            Text(
                categoryLabel(hit.category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${hit.matchedComponents.size} matched, $blocked blocked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("纳入禁用选择", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            info?.description?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard("描述") { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }
            info?.sourceLink?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard("相关链接") {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard("可安全禁用") {
                Text(
                    if (info?.safeToBlock == true) "是" else "否（谨慎评估）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard("副作用") {
                Text(
                    info?.sideEffect?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && it != "未知" }
                        ?: "未知",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard("开发者团队 / 置信度") {
                Text(
                    listOf(
                        info?.devTeam?.ifBlank { info?.company }?.takeIf { it.isNotBlank() },
                        info?.confidence?.let { "置信度 ${confidenceLabel(it)}" }
                    ).filterNotNull().joinToString(" · ").ifBlank { "未知" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            info?.contributors?.takeIf { it.isNotEmpty() }?.let { c ->
                ArchiveFieldCard("规则贡献者") {
                    Text(c.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard("命中组件 ${hit.matchedComponents.size}") {
                Column {
                    val typeOrder = listOf("activity", "service", "receiver", "provider", "other")
                    typeOrder.forEach { t ->
                        val comps = groups[t] ?: return@forEach
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "${typeLabel(t)} × ${comps.size}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        comps.forEach { c ->
                            Text(
                                c,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArchiveFieldCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

/**
 * 倒计时锁定确认按钮（Thanox "Be careful!" 模式）：
 * 首次点击进入倒计时（disabled 递减），归零后才可点确认执行。
 */
@Composable
fun CountdownConfirmTextButton(
    label: String,
    armedLabel: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    seconds: Int = 4
) {
    var armed by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(armed) {
        if (armed) {
            tick = seconds
            while (tick > 0) {
                kotlinx.coroutines.delay(1000)
                tick--
            }
        }
    }
    // 已解锁但 5 秒未确认 → 自动回到锁定态
    LaunchedEffect(armed, tick) {
        if (armed && tick == 0) {
            kotlinx.coroutines.delay(5000)
            if (armed && tick == 0) armed = false
        }
    }
    TextButton(
        onClick = {
            // 倒计时归零前点击无效——锁定语义：数到 0 才放行
            if (armed && tick == 0) {
                armed = false
                onConfirm()
            } else if (!armed) {
                armed = true
            }
        },
        enabled = enabled && (!armed || tick == 0)
    ) {
        Text(
            when {
                armed && tick > 0 -> "$armedLabel（${tick}s）"
                armed -> armedLabel
                else -> label
            },
            color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}
