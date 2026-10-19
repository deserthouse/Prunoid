package io.github.deserthouse.prunoid.ui
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
    var showRestoreConfirm by remember { mutableStateOf(false) }
    // 批E1：未识别勾选提升至屏级——有勾选时底部 SDK 操作栏让位（两套勾选不再并存误触）
    // 批E1/P0-2：未识别勾选集屏级持有，计数由组合直接派生
    // （旧实现 LaunchedEffect(map.size) 在取消勾选时 size 不变 → 计数闩死、底部栏永不恢复）
    val unmatchedSel = remember(app.packageName) { mutableStateMapOf<String, Boolean>() }
    val unmatchedSelCount = unmatchedSel.values.count { it }
    val st by vm.state.collectAsState()
    val pm = LocalContext.current.packageManager
    // 批J1：默认零预选——勾选完全由用户自主（2026-09-26 用户明令撤销 SAFE 预勾）
    var selected by remember(app.packageName) { mutableStateOf(setOf<String>()) }
    // 分类筛选：null = 全部
    // 批P1#14：列表级 SDK 分类筛选传导进详情（用户"只关心广告"的意图不丢失；详情内可再改）
    var catFilter by remember(app.packageName) {
        mutableStateOf(st.filters.catSel.singleOrNull())
    }
    // SDK 档案卡弹层（Blocker/LibChecker 模式）：点卡片打开
    var sheetFor by remember { mutableStateOf<SdkHit?>(null) }
    var detailTab by remember { mutableStateOf(0) }   // 0=SDK 视图 1=组件视图
    var compTypeFilter by remember { mutableStateOf<String?>(null) }  // 组件视图类型过滤
    val appliedEntry = st.applied[app.packageName]
    // 安全分层：白名单命中（框架/核心）= 硬拦截；批X1（审查 R03）：其余系统预装包也一律硬拦
    // （真实包属性判定在引擎执行层 isBlockedTarget，此处 UI 同步收紧——"警告后可操作"路线废除）
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

    // 量化确认摘要（以当前勾选与引擎为准）。
    // 批BB RV-08：单份执行计划——底栏计数/确认框/引擎写入/记账同源；
    // IFW 剔除 provider/native，共享组件按身份去重
    val selHits = app.matchedSdks.filter { it.ruleId in selected }
    val selComponents = selHits.flatMap { it.matchedComponents }
    val effSelComponents = if (st.engine == Engine.IFW) {
        selHits.flatMap { h ->
            h.matchedComponents.filter { c ->
                val t = h.componentTypes[c] ?: ""
                t != "provider" && t != "native"
            }
        }.distinct()
    } else selComponents.distinct()
    // 批C2：按类别归纳影响（组合期预算字符串，供确认句引用）
    val selBreakdown = selHits.groupBy { it.category }
        .mapValues { (_, v) -> v.sumOf { it.matchedComponents.size } }
        .entries.map { (c, n) -> categoryLabel(c) to n }
        .joinToString(" · ") { (label, n) -> "$label × $n" }
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
                        // 批X1（审查 R03）：系统预装包一律硬拦（原"警告后可操作"路线废除）；横幅复用共享组件
                        WarnBanner(stringResource(R.string.sys_block_banner), Modifier.fillMaxWidth(), horizontalPadding = 16.dp)
                    }
                    if (unmatchedSelCount > 0) {
                        // 批E1：未识别勾选进行中——SDK 操作栏让位，防误触另一套操作
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.unmatched_pending_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else Column(Modifier.fillMaxWidth()) {
                        // 批AA7（M3E 评审 E11）：选择计数随勾选持续可见——滚动后仍知道选了什么；
                        // M=当前引擎实际支持组件数（IFW 剔除 provider/native，与 Y2 记账口径同源）
                        if (selected.isNotEmpty()) {
                            Text(
                                stringResource(R.string.sel_count_line, selHits.size, effSelComponents.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
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
                                enabled = !framework && !systemWarn && !st.busy && selected.isNotEmpty()
                                    && st.workMode.capabilities.disablePerApp && st.rootGranted,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (st.busy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (selected.isNotEmpty()) stringResource(R.string.apply_selected, selected.size) else stringResource(R.string.apply_rules))
                            }
                            OutlinedButton(
                                // 批D1：全量回滚补确认
                                onClick = { showRestoreConfirm = true },
                                enabled = !framework && !systemWarn && !st.busy && appliedEntry != null
                                    && st.workMode.capabilities.disablePerApp && st.rootGranted,
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.restore)) }
                        }
                        // 批R1（审计 B-1）：禁用态必须给原因——零反馈死按钮是走查最高优先缺陷
                        if (!framework && !st.rootGranted) {
                            Text(
                                stringResource(R.string.apply_need_root),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 10.dp)
                            )
                        }
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
            item { DetailHeaderCard(app, st, appliedEntry, pm, versionName) { sheetFor = it } }
            item {
                Column {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Engine.entries.forEachIndexed { i, e ->
                            SegmentedButton(
                                selected = st.engine == e,
                                onClick = { vm.selectEngine(e) },
                                enabled = !st.busy,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = i, count = Engine.entries.size
                                )
                            ) { Text(engineLabel(e)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 批CC（R5）：引擎说明默认单行摘要、点击展开全文——
                    // 选择任务优先，长机制说明不再占据首屏高度
                    var engineHelpOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { engineHelpOpen = !engineHelpOpen }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (st.engine == Engine.IFW)
                                stringResource(R.string.ifw_desc)
                            else
                                stringResource(R.string.pm_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (engineHelpOpen) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = if (engineHelpOpen) 180f else 0f }
                        )
                    }
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
                    // 批L3：SDK 视图搜索（批Q4 统一组件）；批N2⑥ 作用域明确
                    SearchField(
                        value = sdkQuery,
                        onValueChange = { sdkQuery = it },
                        placeholder = { Text(stringResource(R.string.search_sdk_in_app)) },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.showing_sdks, visibleSdks.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.precheck_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { selected = selected + visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty() && !st.busy
                            ) { Text(stringResource(R.string.select_all_filtered)) }
                            TextButton(
                                onClick = { selected = selected - visibleSdks.map { it.ruleId }.toSet() },
                                enabled = visibleSdks.isNotEmpty() && !st.busy
                            ) { Text(stringResource(R.string.select_none)) }
                        }
                    }
                }
            }
            if (detailTab == 0) items(visibleSdks, key = { it.ruleId }) { hit ->
                val checked = hit.ruleId in selected
                // 批AA2（M3E 评审 E02 方案一）：整行点击=选择（常用动作优先），行尾独立图标进
                // SDK 档案（原 ExpandMore 暗示原地展开而实为模态档案，图标语义一并修正）；
                // 选中态 tonal 容器持续可见——滚动中可辨认已选集合；风险徽标保持语义色不参与选择反馈
                Card(
                    // 批EE2（六审 B02）：busy 期锁选择面——执行中改选不再进入会被成功回调误清的草稿
                    onClick = { if (!st.busy) selected = if (checked) selected - hit.ruleId else selected + hit.ruleId },
                    Modifier
                        .fillMaxWidth()
                        .animateItem(),
                    colors = if (checked) CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) else CardDefaults.cardColors()
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                if (!st.busy) selected = if (on) selected + hit.ruleId else selected - hit.ruleId
                            },
                            enabled = !st.busy
                        )
                        SdkMonogram(hit.ruleId, hit.name, Modifier.padding(end = 8.dp), vm.ruleInfo(hit.ruleId)?.iconUrl)
                        Column(Modifier.weight(1f)) {
                            // 批P3：审计对象名优先——允许两行
                            Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        IconButton(onClick = { sheetFor = hit }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.cd_open_sheet),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            // 未识别组件（LibChecker "Unmarked library" 语义）：只读展示，供人审与规则仓 PR
            if (detailTab == 0 && app.unmatched.isNotEmpty()) {
                item(key = "unmatched") {
                    UnmatchedCard(app, unmatchedSel, detailListState, vm) { msg = it }
                }
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_app_confirm)) },
            confirmButton = {
                // 批J2：单次确认（用户明令拆除倒计时）
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreApp(app) { msg = it }
                    }
                ) { Text(stringResource(R.string.restore), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
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
            title = {
                // 批P5：M3 对话框标题左对齐（judge ⑬）；批X1：systemWarn 分支移除（系统包已被硬拦到不了这里）
                Text(
                    stringResource(R.string.apply_rules),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )
            },
            text = {
                Column {
                    Text(
                        // 批U（审查U-6）：分隔空格移进 excluded_line 资源（中文全角括号自带断句不需要半角空格）
                        stringResource(R.string.confirm_line, selHits.size, effSelComponents.size) +
                            (if (excludedRisky > 0) stringResource(R.string.excluded_line, excludedRisky) else "") +
                            stringResource(R.string.engine_line, st.engine.name)
                    )
                    if (selBreakdown.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            selBreakdown,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                // 批J2：单次确认（用户明令拆除倒计时）
                TextButton(
                    onClick = {
                        showApplyConfirm = false
                        // 批X2（审查 PN-06）：成功才清空选择——失败保留重试入口
                        // 批EE2（六审 B02）：只从当前选择移除本次提交集——busy 窗口内新增的勾选保留
                        val committed = selected
                        vm.applyRules(app, committed) { ok, m ->
                            msg = m
                            if (ok) selected = selected - committed
                        }
                    }
                ) { Text(stringResource(R.string.apply), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun DetailHeaderCard(
    app: ScannedApp,
    st: AppUiState,
    appliedEntry: io.github.deserthouse.prunoid.core.engine.AppliedRulesStore.AppliedEntry?,
    pm: android.content.pm.PackageManager,
    versionName: String,
    onOpenSheet: (SdkHit) -> Unit
) {
    // 批AA1（M3E 评审 E01）：技术元数据（Target/Min/体积/时间/快捷头像）折叠——
    // 首屏让位给"对象+识别范围+当前状态"，选择任务优先
    var techOpen by remember { mutableStateOf(false) }
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
                                // 批AA1：Target/Min/体积/安装时间折叠进"技术详情"（首屏让位选择任务）
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
                                                    .background(safetyColors(s, isDark()).container, RoundedCornerShape(50))
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
                        // 批AA1：已检出 SDK 快捷条折叠进技术详情（与下方列表功能重复，档案入口由
                        // 选择行行尾图标承担）
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
                        // 批AA1：技术详情折叠区——Target/Min/体积、安装/更新时间、SDK 快捷条
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { techOpen = !techOpen }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 批CC（R5）：箭头随展开旋转——展开方向可读
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = if (techOpen) 180f else 0f }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.detail_tech),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (techOpen) {
                            Column(Modifier.fillMaxWidth()) {
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
                                // 已检出 SDK 快捷条（点头像直达档案卡）
                                if (app.matchedSdks.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        app.matchedSdks.take(10).forEach { hit ->
                                            // 批F2/PD2：48dp 最小触达 + clip(CircleShape) 修圆头像方波纹
                                            SdkMonogram(
                                                hit.ruleId, hit.name,
                                                Modifier
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .clickable { onOpenSheet(hit) }
                                                    .padding(8.dp)
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
                            }
                        }
            }
}

}

/** 未识别组件卡（批G2b 自 AppDetailScreen 抽出）：展开滚动定位/卡内搜索/疑似筛选/
 *  勾选批量禁用（error 语义色）/组件清单分享。勾选集屏级持有（底部栏让位逻辑共用）。 */
@Composable
private fun UnmatchedCard(
    app: ScannedApp,
    unmatchedSel: SnapshotStateMap<String, Boolean>,
    detailListState: androidx.compose.foundation.lazy.LazyListState,
    vm: AppViewModel,
    onMessage: (String) -> Unit
) {
                    var unmatchedOpen by remember { mutableStateOf(false) }
                    // 批R2（审计 P-3）：批量禁用未识别组件与 Apply 同级风险，补单次确认
                    var confirmUnmatched by remember { mutableStateOf(false) }
                    // 批X4（审查 PN-20）：能力/忙碌门控所需状态
                    val unmatchedState by vm.state.collectAsState()
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
                                // 批E2：勾选后卡头就近禁用（免滚 20 行找按钮）
                                val selN = unmatchedSel.count { it.value }
                                val unmatchedBusy = unmatchedState.busy
                                val unmatchedAllowed = unmatchedState.rootGranted && unmatchedState.workMode.capabilities.disablePerApp && !unmatchedBusy
                                if (unmatchedOpen && selN > 0) {
                                    // 批N2①：批量禁用属危险操作，用 error 语义色
                                    Button(
                                        onClick = { confirmUnmatched = true },
                                        enabled = unmatchedAllowed,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(stringResource(R.string.disable_selected_n, selN), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.width(6.dp))
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
                                                    color = safetyColors(Safety.CAUTION, isDark()).container,
                                                    contentColor = safetyColors(Safety.CAUTION, isDark()).onContainer,
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
                                            // 批N2②：改全词（Activity×3），用屏级 lookup 表避免 lambda 内调 composable
                                            buildString {
                                                g.componentTypes.values.groupingBy { it }.eachCount()
                                                    .entries.sortedByDescending { it.value }
                                                    .forEachIndexed { idx, (t, n) ->
                                                        if (idx > 0) append(" ")
                                                        append(tagText(t))
                                                        append("×")
                                                        append(n)
                                                    }
                                                append(" · ")
                                                append(g.count)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            // 批U（审查U-3）：前缀省略号顶满 weight(1f) 时与行尾标签零间距，加固定间隙
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                }
                                // 批E2：禁用操作已就近移至卡头（勾选即可见）
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
                    if (confirmUnmatched) {
                        val selGroups = app.unmatched.filter { unmatchedSel[it.prefix] == true }
                        val selCompN = selGroups.flatMap { g -> g.componentTypes.values }.size
                        AlertDialog(
                            onDismissRequest = { confirmUnmatched = false },
                            title = { Text(stringResource(R.string.disable_selected_n, selGroups.size)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.unmatched_confirm_body,
                                        selGroups.size, selCompN, app.label.ifEmpty { app.packageName }
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        confirmUnmatched = false
                                        val byType = selGroups.flatMap { g ->
                                            g.componentTypes.entries.map { (cn, t) -> t to cn }
                                        }.groupBy({ it.first }, { it.second })
                                        // 批X2（审查 PN-06）：失败保留选择重试
                                        vm.disableUnmatched(app.packageName, byType) { ok, m ->
                                            onMessage(m)
                                            if (ok) unmatchedSel.clear()
                                        }
                                    }
                                ) { Text(stringResource(R.string.risk_continue), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = { TextButton(onClick = { confirmUnmatched = false }) { Text(stringResource(R.string.cancel)) } }
                        )
                    }
}
