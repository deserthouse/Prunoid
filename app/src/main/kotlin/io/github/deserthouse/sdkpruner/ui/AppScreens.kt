package io.github.deserthouse.sdkpruner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import io.github.deserthouse.sdkpruner.core.engine.AppliedRulesStore
import io.github.deserthouse.sdkpruner.core.engine.Engine
import io.github.deserthouse.sdkpruner.core.rules.Safety
import io.github.deserthouse.sdkpruner.core.scanner.ScannedApp
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
fun AppListScreen(vm: AppViewModel, onOpen: (ScannedApp) -> Unit, onOpenSettings: () -> Unit) {
    val st by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val snackbar = rememberSnackbar()
    var showSubscribe by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var subMsg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val pm = LocalContext.current.packageManager
    val (subUrl, _) = remember(st) { vm.subscriptionInfo() }

    SnackbarEffect(snackbar, st.message)
    SnackbarEffect(snackbar, subMsg)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SDK-Pruner", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        // 订阅状态点：实心=已订阅，描边=内置快照
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (subUrl != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50)
                                )
                                .semantics {
                                    contentDescription = if (subUrl != null) "已订阅规则源" else "使用内置快照"
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
                        val icon = remember(app.packageName) {
                            runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                        }
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
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text("系统", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
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
            initial = subUrl.orEmpty(),
            onDismiss = { showSubscribe = false },
            onConfirm = { url ->
                showSubscribe = false
                vm.subscribe(url) { subMsg = it }
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
                        url = "https://raw.githubusercontent.com/deserthouse/sdk-pruner-rules/main/rules/snapshot.json"
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
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backups = remember { vm.listBackups() }

    // 双击确认 3 秒未完成自动复位
    LaunchedEffect(confirmClear, confirmRestore) {
        if (confirmClear || confirmRestore) {
            kotlinx.coroutines.delay(3000)
            confirmClear = false
            confirmRestore = false
        }
    }

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
                        "adb shell am broadcast -a io.github.deserthouse.sdkpruner.action.CLEAR_IFW --ez confirm true",
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
                TextButton(
                    onClick = {
                        if (confirmClear) {
                            confirmClear = false
                            vm.clearAllIfw { onMessage(it) }
                        } else confirmClear = true
                    },
                    enabled = !vm.state.collectAsState().value.busy
                ) { Text(if (confirmClear) "再点一次确认清除" else "清除全部 IFW") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        selected?.let { p ->
                            if (confirmRestore) {
                                confirmRestore = false
                                vm.restoreBackup(p) { onMessage(it) }
                                onDismiss()
                            } else confirmRestore = true
                        }
                    },
                    enabled = selected != null && !vm.state.collectAsState().value.busy
                ) { Text(if (confirmRestore) "再点一次确认恢复" else "恢复所选") }
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
    val appliedEntry = st.applied[app.packageName]

    val versionName = remember(app.packageName) {
        runCatching {
            pm.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()
    }.let { if (it.isNullOrBlank()) "无版本号" else it }

    SnackbarEffect(snackbar, msg)

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
                    if (app.isSystem) {
                        // 系统 app：预防式禁用操作区（白名单硬拦截前先解释）
                        Text(
                            "系统 app 为只读：组件由系统框架管理，禁用操作已拦截",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            enabled = !app.isSystem && !st.busy && selected.isNotEmpty(),
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
                            enabled = !app.isSystem && !st.busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("恢复") }
                    }
                }
            }
        }
    ) { padding ->
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
                            val icon = remember(app.packageName) {
                                runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                            }
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
            items(app.matchedSdks, key = { it.ruleId }) { hit ->
                var expanded by remember(hit.ruleId) { mutableStateOf(false) }
                val sideEffect = remember(hit.ruleId) { vm.ruleSideEffect(hit.ruleId) }
                val checked = hit.ruleId in selected
                val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Card(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + hit.ruleId else selected - hit.ruleId
                                },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Column(Modifier.weight(1f).clickable {
                                selected = if (checked) selected - hit.ruleId else selected + hit.ruleId
                            }) {
                                Text(hit.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    categoryLabel(hit.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SafetyBadge(hit.safety)
                            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = if (expanded) "收起" else "展开",
                                    modifier = Modifier.rotate(rot)
                                )
                            }
                        }
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "命中组件 ${hit.matchedComponents.size}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            hit.matchedComponents.forEach { c ->
                                Text(
                                    c,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        sideEffect
                            ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && it != "未知" }
                            ?.let {
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "影响：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                    }
                }
            }
        }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text("应用规则") },
            text = {
                Text(
                    "将禁用 ${selHits.size} 个 SDK 的 ${selComponents.size} 个组件" +
                        (if (excludedRisky > 0) "（已排除 $excludedRisky 个风险项）" else "") +
                        "，引擎 ${st.engine.name}。操作前自动创建备份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    vm.applyRules(app, selected) { msg = it }
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) { Text("取消") }
            }
        )
    }
}

fun safetyLabel(s: Safety) = when (s) {
    Safety.SAFE -> "可安全禁用"
    Safety.CAUTION -> "谨慎禁用"
    Safety.RISKY -> "禁用有风险"
    Safety.UNKNOWN -> "影响未知"
}
