package io.github.deserthouse.prunoid.ui

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
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
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

// M3E 视觉（stringResource(R.string.theme_name)）+ 审查清单落地：
// 应用状态可见 / 手动重扫 / 系统 app 防误操作 / 逐 SDK 勾选 / 量化确认 / busy 态 / 恢复双击确认。
// 包名/组件名/命令一律等宽；浏览=平铺，聚焦=卡片。

private fun safetyIcon(s: Safety): ImageVectorAlias = when (s) {
    Safety.SAFE -> Icons.Outlined.Check
    Safety.CAUTION -> Icons.Outlined.WarningAmber
    Safety.RISKY -> Icons.Outlined.ErrorOutline
    Safety.UNKNOWN -> Icons.Outlined.HelpOutline
}

private typealias ImageVectorAlias = androidx.compose.ui.graphics.vector.ImageVector

/** 列表排序模式（E1 查找力批） */
enum class AppSort(val labelRes: Int) {
    DEFAULT(R.string.sort_default),
    SDK_DESC(R.string.sort_sdk_desc),
    COMPONENT_DESC(R.string.sort_comp_desc),
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name)
}

/** 分类枚举 → 中文（schema 语言不穿透到 UI） */
@Composable
fun categoryLabel(c: String): String = stringResource(when (c) {
    "ads" -> R.string.cat_ads
    "push" -> R.string.cat_push
    "analytics" -> R.string.cat_analytics
    "quality" -> R.string.cat_quality
    "social_or_pay" -> R.string.cat_social
    "maps" -> R.string.cat_map
    "infra" -> R.string.cat_basic
    "security" -> R.string.cat_safety
    "framework" -> R.string.cat_framework
    else -> R.string.cat_other
})

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
    val hasBrand = !iconUrl.isNullOrBlank() || iconRes != null
    // 品牌矢量分支对齐上游 LibChecker：中性浅底 + 原色渲染（tint 会把多色路径染成单色）
    val brandBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .size(32.dp)
            .background(if (hasBrand) brandBg else bgC, RoundedCornerShape(50)),
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
                modifier = Modifier.size(22.dp),
                tint = Color.Unspecified
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
    var subMsg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 订阅点语义：任一源成功拉取过才亮（sources 注册表默认恒含官方源，不能作为判据）
    val subscribed = st.sources.any { it.lastFetched.isNotBlank() }
    // semantics{} 非组合上下文，文案先在组合期解析
    val subDotDesc = if (subscribed) stringResource(R.string.subscribed) else stringResource(R.string.using_snapshot)

    SnackbarEffect(snackbar, st.message)
    SnackbarEffect(snackbar, subMsg)

    // E1 查找力：筛选/排序状态与结果列表提升到 Scaffold 之上，bottomBar 汇总与列表共用同一份
    var catSel by remember { mutableStateOf(setOf<String>()) }
    var safetySel by remember { mutableStateOf<Safety?>(null) }
    var appliedOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(AppSort.DEFAULT) }
    val apps = remember(st.apps, st.showSystem, st.hitsOnly, query, catSel, safetySel, appliedOnly, sortMode, st.applied) {
        st.apps
            .filter { st.showSystem || !it.isSystem }
            .filter { !st.hitsOnly || it.matchedSdks.isNotEmpty() }
            .filter { !appliedOnly || st.applied.containsKey(it.packageName) }
            .filter { catSel.isEmpty() || it.matchedSdks.any { m -> m.category in catSel } }
            .filter { safetySel == null || it.matchedSdks.any { m -> m.safety == safetySel } }
            .filter {
                query.isBlank() || it.label.contains(query, true) ||
                    it.packageName.contains(query, true) ||
                    it.matchedSdks.any { s -> s.name.contains(query, true) }
            }
            .let { list ->
                when (sortMode) {
                    AppSort.DEFAULT -> list
                    AppSort.SDK_DESC -> list.sortedByDescending { it.matchedSdks.size }
                    AppSort.COMPONENT_DESC -> list.sortedByDescending { app -> app.matchedSdks.sumOf { it.matchedComponents.size } }
                    AppSort.RECENT -> list.sortedByDescending { it.lastUpdateTime }
                    AppSort.NAME -> list.sortedBy { it.label.lowercase() }
                }
            }
    }

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
                                    contentDescription = subDotDesc
                                }
                        )
                    }
                },
                actions = {
                    // F1 顶栏精简：库/订阅/恢复入口由底部 tab 与设置页承担；重扫改下拉刷新
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.menu_settings))
                    }
                }
            )
        },
        bottomBar = {
            val hits = apps.count { it.matchedSdks.isNotEmpty() }
            Surface(tonalElevation = 2.dp) {
                Text(
                    if (st.apps.isEmpty()) stringResource(R.string.indexing)
                    else stringResource(R.string.list_summary, apps.size, hits),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    ) { padding ->
        // F1：下拉刷新取代顶栏重扫按钮（重扫进度仍由波浪进度/汇总条呈现）
        PullToRefreshBox(
            isRefreshing = st.scanning,
            onRefresh = { vm.rescan() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
        Column(Modifier.fillMaxSize()) {
            if (!st.rootGranted) {
                Surface(
                    color = if (dark) WarnContainerDark else WarnContainerLight,
                    contentColor = if (dark) WarnOnContainerDark else WarnOnContainerLight
                ) {
                    Text(
                        stringResource(R.string.vm_no_root),
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
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_search))
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
            // 查找力批（E1）：筛选 chips 横滚行 + 排序菜单（不挤顶栏）；状态提升在 Scaffold 之上
            var safetyMenu by remember { mutableStateOf(false) }
            var sortMenu by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = st.hitsOnly,
                    onClick = { vm.toggleHitsOnly() },
                    label = { Text(stringResource(R.string.chip_hits_only)) }
                )
                FilterChip(
                    selected = st.showSystem,
                    onClick = { vm.toggleShowSystem() },
                    label = { Text(stringResource(R.string.chip_show_system)) }
                )
                FilterChip(
                    selected = appliedOnly,
                    onClick = { appliedOnly = !appliedOnly },
                    label = { Text(stringResource(R.string.chip_applied)) }
                )
                listOf("ads", "push", "analytics", "quality", "social_or_pay", "maps", "infra", "security", "framework", "other").forEach { c ->
                    FilterChip(
                        selected = c in catSel,
                        onClick = { catSel = if (c in catSel) catSel - c else catSel + c },
                        label = { Text(categoryLabel(c)) }
                    )
                }
                Box {
                    FilterChip(
                        selected = safetySel != null,
                        onClick = { safetyMenu = true },
                        label = { Text(safetySel?.let { safetyLabel(it) } ?: stringResource(R.string.filter_safety)) }
                    )
                    DropdownMenu(expanded = safetyMenu, onDismissRequest = { safetyMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filter_all)) },
                            onClick = { safetySel = null; safetyMenu = false }
                        )
                        Safety.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(safetyLabel(s)) },
                                onClick = { safetySel = s; safetyMenu = false }
                            )
                        }
                    }
                }
                Box {
                    FilterChip(
                        selected = sortMode != AppSort.DEFAULT,
                        onClick = { sortMenu = true },
                        label = { Text(stringResource(R.string.sort_label)) },
                        leadingIcon = { Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        AppSort.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(stringResource(m.labelRes)) },
                                onClick = { sortMode = m; sortMenu = false }
                            )
                        }
                    }
                }
                if (catSel.isNotEmpty() || safetySel != null || appliedOnly || sortMode != AppSort.DEFAULT) {
                    TextButton(onClick = {
                        catSel = emptySet(); safetySel = null; appliedOnly = false; sortMode = AppSort.DEFAULT
                    }) { Text(stringResource(R.string.filter_clear)) }
                }
            }
            if (st.scanning) {
                LinearWavyProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
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
                        if (query.isBlank()) stringResource(R.string.empty_apps) else stringResource(R.string.empty_no_match),
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
                                                if (framework) stringResource(R.string.badge_framework) else stringResource(R.string.badge_system),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (appliedEntry != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = stringResource(R.string.cd_applied),
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
    }

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
    }.let { if (it.isNullOrBlank()) stringResource(R.string.no_version) else it }

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                            stringResource(R.string.framework_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else if (systemWarn) {
                        Text(
                            stringResource(R.string.system_banner),
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
                            Text(if (selected.isNotEmpty() && selected != defaultSelected) stringResource(R.string.apply_selected, selected.size) else stringResource(R.string.apply_rules))
                        }
                        OutlinedButton(
                            onClick = { vm.restoreApp(app) { msg = it } },
                            enabled = !framework && !st.busy,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.restore)) }
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
                                    stringResource(R.string.detail_summary, versionName, app.matchedSdks.size, app.matchedSdks.sumOf { it.matchedComponents.size }),
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
                                // 安装/更新时间行（AppChecker 走查吸收点；数据取自扫描结果）
                                if (app.lastUpdateTime > 0L) {
                                    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
                                    Text(
                                        buildString {
                                            append(stringResource(R.string.installed_at, fmt.format(java.util.Date(app.firstInstallTime))))
                                            if (app.lastUpdateTime != app.firstInstallTime) {
                                                append(" · ")
                                                append(stringResource(R.string.updated_at, fmt.format(java.util.Date(app.lastUpdateTime))))
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                        // 批G：现场口径行独立于 applied 记账显示（IFW+pm 两处，来源不限本应用）
                        val liveN = st.liveDisabled[app.packageName]?.size ?: -1
                        if (liveN >= 0) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.FactCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.live_line, liveN),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
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
                                    stringResource(R.string.applied_line, e.components.size, e.engine) +
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
                            ) { Text(engineLabel(e)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (st.engine == Engine.IFW)
                            stringResource(R.string.ifw_desc)
                        else
                            stringResource(R.string.pm_desc),
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
                    ) { Text(stringResource(R.string.view_sdk)) }
                    SegmentedButton(
                        selected = detailTab == 1,
                        onClick = { detailTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.view_components)) }
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
                            label = { Text(stringResource(R.string.type_all)) }
                        )
                        listOf("activity" to stringResource(R.string.type_activity), "service" to stringResource(R.string.type_service), "receiver" to stringResource(R.string.type_receiver), "provider" to stringResource(R.string.type_provider)).forEach { (t, l) ->
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
                                label = { Text(stringResource(R.string.filter_all)) }
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
                                stringResource(R.string.showing_sdks, visibleSdks.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { selected = selected + visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty()
                            ) { Text(stringResource(R.string.select_all_filtered)) }
                            TextButton(
                                onClick = { selected = selected - visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty()
                            ) { Text(stringResource(R.string.select_none)) }
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
                            val liveSet = st.liveDisabled[app.packageName]
                            val disN = liveSet?.count { c ->
                                hit.matchedComponents.any { it == c || (app.packageName + "/" + it) == c }
                            } ?: 0
                            Text(
                                if (disN > 0) categoryLabel(hit.category) + " · " + stringResource(R.string.disabled_count, disN)
                                else categoryLabel(hit.category),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (disN > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SafetyBadge(hit.safety)
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(R.string.cd_detail),
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
                                    Text(stringResource(R.string.unmatched_total, total), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(R.string.unmatched_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = if (unmatchedOpen) stringResource(R.string.collapse) else stringResource(R.string.expand),
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
                                                    stringResource(R.string.suspicious),
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
            title = { Text(if (systemWarn) stringResource(R.string.sys_dialog_title) else stringResource(R.string.apply_rules)) },
            text = {
                Column {
                    if (systemWarn) {
                        Text(
                            stringResource(R.string.sys_dialog_body, app.label) +
                                stringResource(R.string.sys_dialog_note),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(R.string.confirm_line, selHits.size, selComponents.size) +
                            (if (excludedRisky > 0) stringResource(R.string.excluded_line, excludedRisky) else "") +
                            stringResource(R.string.engine_line, st.engine.name)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    vm.applyRules(app, selected) { msg = it }
                }) {
                    Text(
                        if (systemWarn) stringResource(R.string.risk_continue) else stringResource(R.string.apply),
                        color = if (systemWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** 引擎显示名（资源化，非组合上下文不可用 label） */
@Composable
fun engineLabel(e: Engine): String = stringResource(if (e == Engine.IFW) R.string.engine_ifw_short else R.string.engine_pm_short)

@Composable
fun typeLabel(t: String): String = stringResource(when (t) {
    "activity" -> R.string.comp_activity
    "service" -> R.string.comp_service
    "receiver" -> R.string.comp_receiver
    "provider" -> R.string.comp_other
    else -> R.string.comp_other
})

@Composable
fun confidenceLabel(c: String): String = if (c == "high" || c == "medium" || c == "low")
    stringResource(when (c) {
        "high" -> R.string.conf_high
        "medium" -> R.string.conf_medium
        else -> R.string.conf_low
    }) else c

@Composable
fun safetyLabel(s: Safety): String = stringResource(when (s) {
    Safety.SAFE -> R.string.safe_safe
    Safety.CAUTION -> R.string.safe_caution
    Safety.RISKY -> R.string.safe_risky
    Safety.UNKNOWN -> R.string.safe_unknown
})


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
                Text(stringResource(R.string.sheet_toggle), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            info?.description?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard(stringResource(R.string.sheet_desc_title)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }
            info?.sourceLink?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard(stringResource(R.string.sheet_links)) {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard(stringResource(R.string.sheet_safe)) {
                Text(
                    if (info?.safeToBlock == true) stringResource(R.string.yes) else stringResource(R.string.no_caution),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard(stringResource(R.string.sheet_sideeffect)) {
                Text(
                    info?.sideEffect?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && it != "未知" }
                        ?: stringResource(R.string.unknown),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard(stringResource(R.string.sheet_dev)) {
                Text(
                    listOf(
                        info?.devTeam?.ifBlank { info?.company }?.takeIf { it.isNotBlank() },
                        info?.confidence?.let { stringResource(R.string.conf_label, confidenceLabel(it)) }
                    ).filterNotNull().joinToString(" · ").ifBlank { stringResource(R.string.unknown) },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            info?.contributors?.takeIf { it.isNotEmpty() }?.let { c ->
                ArchiveFieldCard(stringResource(R.string.sheet_contributors)) {
                    Text(c.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard(stringResource(R.string.sheet_matched, hit.matchedComponents.size)) {
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
