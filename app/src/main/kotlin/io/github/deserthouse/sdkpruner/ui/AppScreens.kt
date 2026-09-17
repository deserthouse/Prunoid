package io.github.deserthouse.sdkpruner.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.sdkpruner.core.engine.Engine
import io.github.deserthouse.sdkpruner.core.rules.Safety
import io.github.deserthouse.sdkpruner.core.scanner.ScannedApp

// M3E 视觉重做（2026-09-17 方案获批）：
// 冷静的审计台——数据安静承载，表达力只用在状态上（扫描波浪线 / 安全徽标 / Snackbar）。
// 包名/组件名/命令一律等宽；浏览=平铺，聚焦=卡片。

private fun safetyIcon(s: Safety): ImageVector = when (s) {
    Safety.SAFE -> Icons.Outlined.Check
    Safety.CAUTION -> Icons.Outlined.WarningAmber
    Safety.RISKY -> Icons.Outlined.ErrorOutline
    Safety.UNKNOWN -> Icons.Outlined.HelpOutline
}

/** 四级安全徽标：色点语义 + 图标 + 文字（色不单独表意） */
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
            Text(safetyLabel(s), style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
        }
    }
}

/** 结果提示统一走 Snackbar（取代平铺裸文本） */
@Composable
fun rememberSnackbar(): SnackbarHostState = remember { SnackbarHostState() }

@Composable
fun SnackbarEffect(snackbar: SnackbarHostState, message: String?) {
    LaunchedEffect(message) {
        message?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it, withDismissAction = true) }
    }
}

// ─────────────────────────── 列表屏 ───────────────────────────

@Composable
fun AppListScreen(vm: AppViewModel, onOpen: (ScannedApp) -> Unit) {
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
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSubscribe = true }) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "规则订阅源")
                    }
                    IconButton(onClick = { showRecovery = true }) {
                        Icon(Icons.Outlined.HealthAndSafety, contentDescription = "备份与应急恢复")
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
            // 搜索框：胶囊填充形态
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索 app / 包名 / SDK") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = st.showSystem,
                    onClick = { vm.toggleShowSystem() },
                    label = { Text("含系统 app（只读）") }
                )
            }
            if (st.scanning) {
                // M3E 波浪线性进度：表达力只用在状态上
                LinearWavyProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            val apps = remember(st.apps, st.showSystem, query) {
                st.apps
                    .filter { st.showSystem || !it.isSystem }
                    .filter {
                        query.isBlank() || it.label.contains(query, true) ||
                            it.packageName.contains(query, true) ||
                            it.matchedSdks.any { s -> s.name.contains(query, true) }
                    }
            }
            if (apps.isEmpty() && !st.scanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        ListItem(
                            headlineContent = { Text(app.label.ifEmpty { app.packageName }) },
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
                                        label = { Text("${app.matchedSdks.size} SDK") }
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
    val backups = remember { vm.listBackups() }
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
                // 紧急通道：等宽代码块样式
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
                // 双击确认：第一击只置位，第二击执行；3 秒未确认自动复位
                LaunchedEffect(confirmClear) {
                    if (confirmClear) {
                        kotlinx.coroutines.delay(3000)
                        confirmClear = false
                    }
                }
                TextButton(
                    onClick = {
                        if (confirmClear) {
                            confirmClear = false
                            vm.clearAllIfw { onMessage(it) }
                        } else {
                            confirmClear = true
                        }
                    }
                ) { Text(if (confirmClear) "再点一次确认" else "清除全部 IFW") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        selected?.let { p ->
                            vm.restoreBackup(p) { onMessage(it) }
                            onDismiss()
                        }
                    },
                    enabled = selected != null
                ) { Text("恢复所选") }
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

    SnackbarEffect(snackbar, msg)

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
            // 操作区底部固定
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showApplyConfirm = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("应用规则") }
                    OutlinedButton(
                        onClick = { vm.restoreApp(app) { msg = it } },
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头部卡片
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val pm = LocalContext.current.packageManager
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
                            val comps = app.matchedSdks.sumOf { it.matchedComponents.size }
                            Text(
                                "命中 ${app.matchedSdks.size} SDK · $comps 组件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            // 双引擎切换：SegmentedButton + 差异说明
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
            // SDK 卡片：分类徽标 + 安全徽标 + 可展开组件清单
            items(app.matchedSdks, key = { it.ruleId }) { hit ->
                var expanded by remember(hit.ruleId) { mutableStateOf(false) }
                val sideEffect = remember(hit.ruleId) { vm.ruleSideEffect(hit.ruleId) }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable { expanded = !expanded }
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    hit.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SafetyBadge(hit.safety)
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = if (expanded) "收起" else "展开",
                                modifier = Modifier.padding(start = 6.dp)
                            )
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

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text("应用规则") },
            text = { Text("将对 SAFE / CAUTION 级 SDK 组件应用 ${st.engine.name} 规则（操作前自动创建备份）。") },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    vm.applyRules(app) { msg = it }
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
