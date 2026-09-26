package io.github.deserthouse.prunoid.ui

import io.github.deserthouse.prunoid.BuildConfig

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp

/** 列表排序模式（E1 查找力批） */
enum class AppSort(val labelRes: Int) {
    DEFAULT(R.string.sort_default),
    SDK_DESC(R.string.sort_sdk_desc),
    COMPONENT_DESC(R.string.sort_comp_desc),
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name)
}
// ─────────────────────────── 列表屏 ───────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppListScreen(vm: AppViewModel, onOpen: (ScannedApp) -> Unit, onOpenSettings: () -> Unit, onOpenLibrary: () -> Unit) {
    val st by vm.state.collectAsState()
    val dark = isDark()
    val snackbar = rememberSnackbar()
    var subMsg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 订阅点语义：任一源成功拉取过才亮（sources 注册表默认恒含官方源，不能作为判据）
    val subscribed = st.sources.any { it.lastFetched.isNotBlank() }
    // semantics{} 非组合上下文，文案先在组合期解析
    val subDotDesc = if (subscribed) stringResource(R.string.subscribed) else stringResource(R.string.using_snapshot)

    SnackbarEffect(snackbar, st.message, st.msgSeq)
    SnackbarEffect(snackbar, subMsg)
    // 批A1 首启引导；批F2/PD3：搜索聚焦即收卡，失焦+空词恢复（OptIcon C2 同款）
    var searchFocused by remember { mutableStateOf(false) }
    val showGuide = !st.guided && st.apps.isNotEmpty() && !searchFocused

    // E1 查找力：筛选/排序状态与结果列表提升到 Scaffold 之上，bottomBar 汇总与列表共用同一份
    // 批T8/G2c：筛选族统一入 VM 会话态（AppUiState.filters），导航往返保留
    val catSel = st.filters.catSel
    val safetySel = st.filters.safety
    val appliedOnly = st.filters.appliedOnly
    var sortMode by remember { mutableStateOf(AppSort.DEFAULT) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val apps = remember(st.apps, st.filters.showSystem, st.filters.hitsOnly, query, catSel, safetySel, appliedOnly, sortMode, st.applied) {
        st.apps
            .filter { st.filters.showSystem || !it.isSystem }
            .filter { !st.filters.hitsOnly || it.matchedSdks.isNotEmpty() }
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

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    // 批I1（OptIcon 样板）：serif 艺术字 wordmark + stage 版本徽章；点标题回顶
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                    ) {
                        Text(
                            "Prunoid",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.width(10.dp))
                        // 版本徽章：stage 变色（alpha=tertiary / beta=secondary / 正式=primary）
                        val stage = BuildConfig.VERSION_NAME.substringAfter('-', "")
                        val (badgeBg, badgeFg) = when (stage) {
                            "alpha" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                            "beta" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        }
                        Surface(shape = MaterialTheme.shapes.small, color = badgeBg) {
                            Text(
                                "v" + BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 批#21：audit 模式全局可读标识
                        if (st.workMode.id.name == "AUDIT") {
                            Text(
                                stringResource(R.string.audit_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 订阅状态点（批T4）：实心=有源拉取过；空心描边=仅内置快照
                        if (subscribed) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .semantics { contentDescription = subDotDesc }
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                    .semantics { contentDescription = subDotDesc }
                            )
                        }
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
            if (showGuide) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.guide_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { vm.dismissGuide() }) {
                            Text(stringResource(R.string.guide_got_it))
                        }
                    }
                }
            }
            // 批L2：滚动收起搜索区（上滑即回，OptIcon 同款语义）
            val scrollUp by remember { derivedStateOf { listState.scrollingUp } }
            val headerVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 || scrollUp } }
            AnimatedVisibility(
                visible = headerVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
            )
            // 批L1：筛选收敛为两入口——Filter（sheet）+ Sort（menu），不再横滚找排序
            var sortMenu by remember { mutableStateOf(false) }
            val activeFilterCount = catSel.size + (if (safetySel != null) 1 else 0) + (if (appliedOnly) 1 else 0) +
                (if (st.filters.hitsOnly) 1 else 0) + (if (st.filters.showSystem) 1 else 0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = { showFilterSheet = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (activeFilterCount > 0) stringResource(R.string.filter_label_n, activeFilterCount)
                         else stringResource(R.string.filter_label))
                }
                Box(Modifier.weight(1f)) {
                    FilledTonalButton(onClick = { sortMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(sortMode.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    // 批审查#3：排序钮加下拉尾标，与筛选钮区分
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        AppSort.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(stringResource(m.labelRes)) },
                                onClick = { sortMode = m; sortMenu = false }
                            )
                        }
                    }
                }
            }
            }  // AnimatedVisibility Column end（批L2）
            }  // AnimatedVisibility end（批L2）
            if (showFilterSheet) {
                ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
                    Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp).verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.filter_label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearListFilters() }) { Text(stringResource(R.string.filter_clear)) }
                        }
                        SectionLabel(stringResource(R.string.filter_scope))
                        // 批S3：Row→FlowRow，三 chip 溢出不再把末位压成逐字断行
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            FilterChip(selected = st.filters.hitsOnly, onClick = { vm.toggleHitsOnly() }, label = { Text(stringResource(R.string.chip_hits_only)) })
                            FilterChip(selected = st.filters.showSystem, onClick = { vm.toggleShowSystem() }, label = { Text(stringResource(R.string.chip_show_system)) })
                            FilterChip(selected = appliedOnly, onClick = { vm.toggleListAppliedOnly() }, label = { Text(stringResource(R.string.chip_applied)) })
                        }
                        Spacer(Modifier.height(8.dp))
                        SectionLabel(stringResource(R.string.filter_safety))
                        // FlowRow 换行：四 chip 一行挤不下导致末位竖排塌陷（judge 抓出）
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            Safety.entries.forEach { sf ->
                                FilterChip(
                                    selected = safetySel == sf,
                                    onClick = { vm.toggleListSafety(sf) },
                                    label = { Text(safetyLabel(sf)) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SectionLabel(stringResource(R.string.filter_category))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            ALL_CATEGORIES.forEach { c ->
                                FilterChip(
                                    selected = c in catSel,
                                    onClick = { vm.toggleListCat(c) },
                                    label = { Text(categoryLabel(c)) }
                                )
                            }
                        }
                    }
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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
                                            shape = MaterialTheme.shapes.small
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
                                Column {
                                    Text(
                                        app.packageName,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 批P1#7：搜索命中原因（SDK 名命中时提示，避免"搜 tencent 出 6 个 app"困惑）
                                    if (query.length >= 3 && app.label?.contains(query, true) != true &&
                                        !app.packageName.contains(query, true)
                                    ) {
                                        val via = app.matchedSdks.firstOrNull { it.name.contains(query, true) }
                                        if (via != null) {
                                            Text(
                                                stringResource(R.string.matched_via, via.name),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
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
                                    // 批I2（M3E）：元数据徽标=tonal Surface（非 SuggestionChip——那是输入建议语义）；
                                    // 色点带 surface 描边环保证浅底可见；行整体点击进详情，徽标不再重复交互
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (worst != null) Box(
                                                    Modifier
                                                        .size(8.dp)
                                                        .background(safetyColors(worst, dark).container, RoundedCornerShape(50))
                                                        .border(1.dp, MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    pluralStringResource(R.plurals.badge_sdks, app.matchedSdks.size, app.matchedSdks.size),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            val adsN = app.matchedSdks.count { it.category == "ads" }
                                            val pushN = app.matchedSdks.count { it.category == "push" }
                                            if (adsN + pushN > 0) {
                                                Text(
                                                    stringResource(R.string.badge_ads_push_short, adsN, pushN),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
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

