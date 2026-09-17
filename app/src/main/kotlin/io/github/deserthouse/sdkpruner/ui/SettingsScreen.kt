package io.github.deserthouse.sdkpruner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.deserthouse.sdkpruner.core.engine.Engine
import io.github.deserthouse.sdkpruner.BuildConfig

// 设置页（2026-09-17 审查后新增）：默认引擎 / 备份保留份数 / 订阅源详情与管理 / 应急通道 / 关于。
// 键值事实源在 SettingsRepository（DataStore），此处只做读写呈现。

// ISO 时间戳 → 本地可读格式；解析失败原样返回
private fun fmtFetched(iso: String): String = runCatching {
    val t = java.time.Instant.parse(iso)
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(t)
}.getOrDefault(iso)

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var showSubscribe by remember { mutableStateOf(false) }
    val meta = remember(st) { vm.subscriptionMeta() }

    SnackbarEffect(snackbar, msg)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 禁用引擎原理与区别 ───────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("两种禁用引擎的区别", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("IFW（意图防火墙，无感知）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "向 /data/system/ifw/ 写入拦截规则，在系统框架层拦下组件的启动请求。" +
                            "应用完全感知不到，也无法自行恢复；规则文件独立于 APK，应用更新后依然生效。推荐日常使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("pm disable（组件停用，跨更新）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "把组件置为系统级“已停用”状态。兼容性好、状态可查，但应用能检测到组件被禁并可能自行恢复；" +
                            "停用状态同样跨应用更新保留。适合排查 IFW 行为异常的兼容性场景。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "两种引擎都只针对第三方应用组件，操作前自动备份；恢复入口在列表页应急菜单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ── 默认引擎 ─────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("默认禁用引擎", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Engine.entries.forEachIndexed { i, e ->
                            SegmentedButton(
                                selected = st.engine == e,
                                onClick = { vm.setDefaultEngine(e.name) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = Engine.entries.size)
                            ) { Text(e.label) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "启动时使用此引擎；详情页可临时切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ── 备份保留份数 ─────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("备份保留份数", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    var keepLocal by remember(st.backupKeep) { mutableStateOf(st.backupKeep.toFloat()) }
                    Text(
                        "当前保留最近 ${keepLocal.toInt()} 份（3–30）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = keepLocal,
                        onValueChange = { keepLocal = it },
                        // 松手才落盘，避免拖动过程高频写 DataStore
                        onValueChangeFinished = { vm.setBackupKeep(keepLocal.toInt()) },
                        valueRange = 3f..30f,
                        steps = 26
                    )
                }
            }
            // ── 规则订阅 ─────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("规则订阅", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (meta != null) {
                        Text(
                            meta.url,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${meta.sdkCount} 条规则 · 拉取于 ${fmtFetched(meta.fetchedAt)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.subscribe(meta.url) { msg = it } },
                                enabled = !st.busy
                            ) { Text("检查更新") }
                            OutlinedButton(
                                onClick = { showSubscribe = true },
                                enabled = !st.busy
                            ) { Text("更换源") }
                            OutlinedButton(
                                onClick = { vm.unsubscribe { msg = it } },
                                enabled = !st.busy
                            ) { Text("退订") }
                        }
                    } else {
                        Text(
                            "未订阅，使用内置快照（1929 条）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showSubscribe = true }, enabled = !st.busy) { Text("添加订阅源") }
                    }
                }
            }
            // ── 应急通道 ─────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("应急通道", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "UI 无法进入时，可在电脑上执行以下命令清除全部 IFW 规则：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
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
            }
            // ── 关于 ─────────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "SDK-Pruner v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "SDK 组件审计工具 · Apache-2.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "github.com/deserthouse/sdk-pruner",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showSubscribe) {
        SubscribeDialog(
            initial = meta?.url.orEmpty(),
            onDismiss = { showSubscribe = false },
            onConfirm = { url ->
                showSubscribe = false
                vm.subscribe(url) { msg = it }
            }
        )
    }
}
