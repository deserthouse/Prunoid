package io.github.deserthouse.prunoid.ui
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import java.util.Date
import kotlinx.coroutines.delay

// ─────────────────────────── 详情屏 ───────────────────────────

@Composable
fun AppDetailScreen(app: ScannedApp, vm: AppViewModel, onBack: () -> Unit) {
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    val st by vm.state.collectAsState()
    val pm = LocalContext.current.packageManager
    // 逐 SDK 勾选：默认勾选 SAFE/CAUTION（RISKY/UNKNOWN 需显式加选）
    // 批I：默认只勾 SAFE——CAUTION 及以上由用户显式选择（默认全勾过于激进）
    val defaultSelected = remember(app.packageName) {
        app.matchedSdks.filter { it.safety == Safety.SAFE }.map { it.ruleId }.toSet()
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

    // 分类筛选后的可见 SDK 与勾选摘要（批L3：加视图内搜索）
    var sdkQuery by remember(app.packageName) { mutableStateOf("") }
    val visibleSdks = remember(app.matchedSdks, catFilter, sdkQuery) {
        app.matchedSdks
            .let { l -> if (catFilter == null) l else l.filter { it.category == catFilter } }
            .filter { sdkQuery.isBlank() || it.name.contains(sdkQuery, true) }
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
            // 批T5：组件视图无选择控件，操作栏不显示（Apply 作用对象不明）
            if (detailTab == 0) Surface(tonalElevation = 2.dp) {
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
                            enabled = !framework && !st.busy && selected.isNotEmpty()
                                && st.workMode.capabilities.disablePerApp && st.rootGranted,
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
                            enabled = !framework && !st.busy && appliedEntry != null
                                && st.workMode.capabilities.disablePerApp && st.rootGranted,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.restore)) }
                    }
                }
            }
        }
    ) { padding ->
        var compQuery by remember(app.packageName) { mutableStateOf("") }
        val compRows = remember(app, compTypeFilter, compQuery) {
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
                    if (compTypeFilter != null && t != compTypeFilter) return@mapNotNull null
                    if (compQuery.isNotBlank() && !cn.contains(compQuery, true) && !sdk.name.contains(compQuery, true)) return@mapNotNull null
                    Triple(cn, t, sdk)
                }
            }.sortedBy { it.first }
        }
        val detailListState = rememberLazyListState()
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = detailListState,
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
                            // 批T5：安全分布带文字图例（数字不再靠猜色）
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
                                            Text(
                                                "$n " + safetyLabel(s),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
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
            if (detailTab == 0) {
                item {
                    // 批L3：SDK 视图搜索（批Q4 统一组件）
                    SearchField(value = sdkQuery, onValueChange = { sdkQuery = it }, modifier = Modifier.fillMaxWidth())
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
                item {
                    // 批L3：组件视图搜索（批Q4 统一组件）
                    SearchField(
                        value = compQuery,
                        onValueChange = { compQuery = it },
                        placeholder = { Text(stringResource(R.string.search_comp_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                item(key = "unmatched") {
                    var unmatchedOpen by remember { mutableStateOf(false) }
                    // 展开时卡体常整体落在视口折叠线下（卡顶近屏底），用户只见卡头+分隔线、
                    // 展开体看似"没渲染"——若卡顶已在视口下半区，把卡顶滚动到视口顶。
                    LaunchedEffect(unmatchedOpen) {
                        if (!unmatchedOpen) return@LaunchedEffect
                        val info = detailListState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "unmatched" } ?: return@LaunchedEffect
                        val viewport = detailListState.layoutInfo.viewportEndOffset -
                            detailListState.layoutInfo.viewportStartOffset
                        if (info.offset > viewport * 0.35f) {
                            // 等展开尺寸动画收敛再滚：目标项生长中时 animateScrollToItem 会被打断而中途停（实测 42ms 即返回）
                            var last = -1
                            var guard = 0
                            while (guard++ < 20) {
                                val h = detailListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == "unmatched" }?.size ?: break
                                if (h == last) break
                                last = h
                                delay(50)
                            }
                            detailListState.animateScrollToItem(info.index)
                            // 断言落点：动画若再被打断则瞬时校正到卡顶
                            val after = detailListState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == "unmatched" }
                            if (after != null && after.offset > 2) {
                                detailListState.scrollToItem(info.index)
                            }
                        }
                    }
                    // 动效对齐 Blocker 克制区间（tween 100~200ms，FastOutSlowIn）
                    val rot by animateFloatAsState(
                        targetValue = if (unmatchedOpen) 180f else 0f,
                        animationSpec = MotionTokens.fastFloat,
                        label = "unmatchedArrow"
                    )
                    val total = app.unmatchedTotalComponents
                    val groupsTruncated = app.unmatchedTotalGroups > app.unmatched.size
                    Card(
                        onClick = { unmatchedOpen = !unmatchedOpen },
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize(MotionTokens.fastSize)
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
                                    // P1⑨：unmatched 只带前 20 组，组数超限时明示口径防"少报"误读
                                    if (groupsTruncated) {
                                        Text(
                                            stringResource(R.string.unmatched_truncated, app.unmatched.size, app.unmatchedTotalGroups),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
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
                                // 批L4：搜索 + 疑似筛选 + 可勾选禁用（默认全不选——未验证组件人拍板）
                                var unmatchedQuery by remember { mutableStateOf("") }
                                var suspiciousOnly by remember { mutableStateOf(false) }
                                val unmatchedSel = remember(app.packageName) { mutableStateMapOf<String, Boolean>() }
                                val visibleGroups = app.unmatched.filter { g ->
                                    (unmatchedQuery.isBlank() || g.prefix.contains(unmatchedQuery, true)) &&
                                        (!suspiciousOnly || g.suspicious)
                                }
                                SearchField(
                                    value = unmatchedQuery,
                                    onValueChange = { unmatchedQuery = it },
                                    placeholder = { Text(stringResource(R.string.search_prefix_hint)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = suspiciousOnly,
                                        onClick = { suspiciousOnly = !suspiciousOnly },
                                        label = { Text(stringResource(R.string.suspicious)) }
                                    )
                                }
                                visibleGroups.forEach { g ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = unmatchedSel[g.prefix] == true,
                                            onCheckedChange = { unmatchedSel[g.prefix] = it }
                                        )
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
                                        Text(
                                            " · " + g.count,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // 批L4：勾选了组 → 禁用所选（IFW，按组件类型分组）
                                val selGroups = app.unmatched.filter { unmatchedSel[it.prefix] == true }
                                if (selGroups.isNotEmpty()) {
                                    val selComps = selGroups.flatMap { it.components }
                                    Button(
                                        onClick = {
                                            val byType = selGroups.flatMap { g ->
                                                g.componentTypes.entries.map { (cn, t) -> t to cn }
                                            }.groupBy({ it.first }, { it.second })
                                            vm.disableUnmatched(app.packageName, byType) { msg = it }
                                        },
                                        enabled = !st.busy && selComps.isNotEmpty()
                                            && st.workMode.capabilities.disablePerApp && st.rootGranted,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        Text(stringResource(R.string.disable_unmatched, selComps.size))
                                    }
                                    Text(
                                        stringResource(R.string.unverified_note),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                // 批O：组件清单分享（系统分享器出 JSON，零上传零 token）
                                val ctxShare = androidx.compose.ui.platform.LocalContext.current
                                OutlinedButton(
                                    onClick = {
                                        val json = vm.exportComponentReport(app.packageName)
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Prunoid component report: " + app.label)
                                        }
                                        runCatching {
                                            ctxShare.startActivity(android.content.Intent.createChooser(send, null))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.share_report))
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
                            (if (excludedRisky > 0) " " + stringResource(R.string.excluded_line, excludedRisky) else "") +
                            stringResource(R.string.engine_line, st.engine.name)
                    )
                    // 批R7：默认勾选透明化——用户须知道操作包含预勾选项
                    val defaultSelCount = selected.count { it in defaultSelected }
                    if (defaultSelCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.confirm_defaults, defaultSelCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                // 批S1：破坏性操作统一倒计时守卫（与 Clear IFW/Restore 同族）
                CountdownConfirmTextButton(
                    label = stringResource(if (systemWarn) R.string.risk_continue else R.string.apply),
                    armedLabel = stringResource(R.string.apply_confirm_armed),
                    enabled = true,
                    seconds = 3,
                    onConfirm = {
                        showApplyConfirm = false
                        vm.applyRules(app, selected) { msg = it }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
