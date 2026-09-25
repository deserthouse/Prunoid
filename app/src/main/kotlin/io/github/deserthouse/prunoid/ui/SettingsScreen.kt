package io.github.deserthouse.prunoid.ui

import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.BuildConfig

// 设置页（E3 对齐 OptIcon 版式）：SectionTitle + SettingsCard 分组结构。
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
    val ctx = LocalContext.current
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var showAddSource by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, msg)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.height(4.dp))

            LanguageSection()

            NotifPermSection()

            WorkModeSection(st, vm)

            DeclarativeSection(st, vm)

            EnginesSection(st, vm)

            AutomationBackupSection(st, vm, onShowBackup = { showBackup = true })

            SubscriptionsSection(st, vm, onShowAddSource = { showAddSource = true }, onMsg = { msg = it })

            RecoverySection(st, vm, onMsg = { msg = it })

            AboutSection(st, vm)

            CreditsSection()

            ComplianceSection()

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showBackup) {
        BackupDialog(
            vm = vm,
            onMessage = { msg = it },
            onDismiss = { showBackup = false }
        )
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onConfirm = { name, url ->
                // 批N3：失败不静默——成功才关（成功判定=消息含成功/Fetched）
                vm.addSource(name, url) { msgText ->
                    msg = msgText
                    if (msgText.contains("成功") || msgText.contains("Fetched")) {
                        showAddSource = false
                    }
                }
            }
        )
    }
}

/** OptIcon 同款致谢条目：项目名 + 描述 + 可点链接 */
@Composable
private fun CreditEntry(project: String, description: String, url: String) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(project, style = MaterialTheme.typography.bodyLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
                        )
                    }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(url.removePrefix("https://"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Launch, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** OptIcon 同款分组标题（卡片外的小节标题） */
@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** OptIcon 同款分组卡片 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(content = content) }
}

/** OptIcon 同款设置行：图标 + 标题 + 副标题 + 尾部动作 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        action?.invoke()
    }
}


@Composable
fun BackupDialog(vm: AppViewModel, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    // F3：备份=可选机制。对话框披露用法/路径/恢复点/份数（自由填写，不再滑杆）
    val backups = remember { vm.listBackups() }
    // 批J#3：备份快照是全量状态，多选恢复语义不成立——改单选（最后选中者生效）
    var selected by remember { mutableStateOf<String?>(null) }
    var keepText by remember { mutableStateOf(vm.state.value.backupKeep.toString()) }
    var confirmRestore by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_entry_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.backup_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.backup_path_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "/data/data/io.github.deserthouse.prunoid/files/backups/",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(R.string.backup_path_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.backup_points_title), style = MaterialTheme.typography.titleSmall)
                if (backups.isEmpty()) {
                    Text(
                        stringResource(R.string.recovery_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    backups.forEach { path ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected == path,
                                onCheckedChange = { on -> if (on) selected = path }
                            )
                            Text(
                                path.substringAfterLast("/"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(
                        onClick = { confirmRestore = true },
                        enabled = selected != null
                    ) { Text(stringResource(R.string.restore_selected)) }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.backup_keep_title), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keepText,
                        onValueChange = { v -> keepText = v.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val n = keepText.toIntOrNull()?.coerceIn(1, 99999) ?: 10
                            vm.setBackupKeep(n)
                            onMessage("")
                        },
                        enabled = keepText.toIntOrNull() != null
                    ) { Text(stringResource(R.string.add)) }
                }
                Text(
                    stringResource(R.string.backup_keep_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore_selected)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                // 批S1 补漏（二轮架构复审抓出）：破坏性操作统一倒计时守卫
                CountdownConfirmTextButton(
                    label = stringResource(R.string.restore),
                    armedLabel = stringResource(R.string.restore_confirm_armed),
                    enabled = true,
                    seconds = 3,
                    onConfirm = {
                        confirmRestore = false
                        vm.restoreBackup(selected!!) { onMessage(it) }
                        selected = null
                        onDismiss()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}


@Composable
fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // 批N3：提交中状态（失败不静默，成功关闭）
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            Column {
                Text(stringResource(R.string.add_source_desc))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name_optional)) }
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") }
                )
                Spacer(Modifier.height(6.dp))
                // 批T3：自建源置信度与信任披露
                Text(
                    stringResource(R.string.addsource_disclosure),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        submitting = true
                        onConfirm(name.trim(), url.trim())
                    }
                },
                enabled = url.startsWith("http")
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
@Composable
private fun NotifPermSection() {
    // F1：权限状态行。点击跳系统「应用通知」页——自动弹窗在用户点过 Don't allow 后
    // 永不再现（MainActivity 记忆），这里是唯一的反悔通道。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sdk33 = android.os.Build.VERSION.SDK_INT >= 33
    var granted by remember {
        mutableStateOf(
            !sdk33 || androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    // 从系统设置返回时刷新状态（页面在组合内切换、不触发 recreate，需生命周期钩子）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = !sdk33 || androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    SectionTitle(stringResource(R.string.notif_perm_title))
    SettingsCard {
        SettingRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.notif_perm_title),
            subtitle = when {
                !sdk33 -> stringResource(R.string.notif_perm_legacy)
                granted -> stringResource(R.string.notif_perm_granted)
                else -> stringResource(R.string.notif_perm_denied)
            },
            onClick = if (sdk33 && !granted) ({
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                })
            }) else null
        ) {
            Icon(
                if (granted || !sdk33) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (granted || !sdk33) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun LanguageSection() {
    // ── 语言（批I：手动覆盖，立即 recreate 生效） ──
    SectionTitle(stringResource(R.string.lang_section))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                // 批T7：迁官方 per-app locales（AppCompatDelegate），与系统「应用语言」入口同源
                val cur = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val opts = listOf("" to "System", "zh-CN" to "中文", "en" to "English")
                opts.forEachIndexed { i, (tag, label) ->
                    val selectedNow = if (cur.startsWith("zh")) "zh-CN" else if (cur.startsWith("en")) "en" else ""
                    SegmentedButton(
                        selected = selectedNow == tag,
                        onClick = {
                            if (selectedNow != tag) {
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                                )
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opts.size)
                    ) { Text(label) }
                }
            }
        }
    }

}
@Composable
private fun WorkModeSection(st: AppUiState, vm: AppViewModel) {
    // ── 工作方式（批N 一级）──
    SectionTitle(stringResource(R.string.sec_workmode))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("root" to stringResource(R.string.wm_root), "audit" to stringResource(R.string.wm_audit)).forEachIndexed { i, (tag, label) ->
                    SegmentedButton(
                        selected = (st.workMode.id.name == "ROOT") == (tag == "root"),
                        onClick = { vm.setWorkMode(tag) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (st.workMode.id.name == "ROOT") stringResource(R.string.wm_root_desc)
                else stringResource(R.string.wm_audit_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

}
@Composable
private fun DeclarativeSection(st: AppUiState, vm: AppViewModel) {
    // ── 声明式（批P：LSPosed 模式，默认关闭） ──
    SectionTitle(stringResource(R.string.sec_declarative))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.decl_title),
                subtitle = if (st.workMode.id.name == "ROOT" || st.workMode.id.name == "AUDIT")
                    stringResource(R.string.decl_status, vm.declared.size)
                else stringResource(R.string.decl_status, vm.declared.size)
            ) {
                Switch(checked = vm.declarationsEnabled, onCheckedChange = { vm.setDeclarationsEnabled(it) })
            }
            Text(
                stringResource(R.string.decl_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                stringResource(R.string.decl_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

}
@Composable
private fun EnginesSection(st: AppUiState, vm: AppViewModel) {
    // ── 禁用引擎（批N：仅 root 模式显示，二级级联） ──
    if (st.workMode.id.name == "ROOT") {
    SectionTitle(stringResource(R.string.sec_engine))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.engine_default_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Engine.entries.forEachIndexed { i, e ->
                    SegmentedButton(
                        selected = st.engine == e,
                        onClick = { vm.setDefaultEngine(e.name) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = Engine.entries.size)
                    ) { Text(engineLabel(e)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.engine_default_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.engines_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.engine_ifw_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(
                // aapt2 会剥资源字符串尾部空格，拼接空格只能在这里补
                stringResource(R.string.engine_ifw_body1) + " " +
                    stringResource(R.string.engine_ifw_body2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.engine_pm_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.engine_pm_body1) + " " +
                    stringResource(R.string.engine_pm_body2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.engines_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    }

}
@Composable
private fun AutomationBackupSection(st: AppUiState, vm: AppViewModel, onShowBackup: () -> Unit) {
    // ── 自动化与备份（批N：仅 root 模式） ──
    if (st.workMode.id.name == "ROOT") {
    SectionTitle(stringResource(R.string.sec_auto))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(
                icon = Icons.Outlined.Autorenew,
                title = stringResource(R.string.auto_title),
                subtitle = stringResource(R.string.auto_summary),
                subtitleMaxLines = 4
            ) {
                Switch(checked = st.autoReapply, onCheckedChange = { vm.setAutoReapply(it) })
            }
            if (st.autoReapply) {
                // 批N：重应用档位（realtime=常驻服务/open=启动对账，默认 open）
                Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow {
                        listOf("open" to stringResource(R.string.reapply_open), "realtime" to stringResource(R.string.reapply_realtime)).forEachIndexed { i, (tag, label) ->
                            SegmentedButton(
                                selected = st.reapplyMode == tag,
                                onClick = { vm.setReapplyMode(tag) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2)
                            ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Text(
                    if (st.reapplyMode == "realtime") stringResource(R.string.reapply_realtime_desc)
                    else stringResource(R.string.reapply_open_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp)
                )
            }
            if (!st.autoReapply) {
                Text(
                    stringResource(R.string.auto_off_note1) +
                        stringResource(R.string.auto_off_note2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // 批N：备份默认关闭——开关行 + 入口行（开关开后 apply 前才 tar）
            SettingRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.backup_entry_title),
                subtitle = if (st.backupEnabled) stringResource(R.string.backup_entry_sub, st.backupKeep)
                           else stringResource(R.string.backup_off_sub),
                onClick = onShowBackup
            ) {
                Switch(checked = st.backupEnabled, onCheckedChange = { vm.setBackupEnabled(it) })
            }
        }
    }

    }

}
@Composable
private fun SubscriptionsSection(st: AppUiState, vm: AppViewModel, onShowAddSource: () -> Unit, onMsg: (String) -> Unit) {
    // ── 规则订阅 ──
    SectionTitle(stringResource(R.string.sec_sub))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.sub_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (st.snapshotMeta.isNotBlank()) {
                Text(
                    stringResource(R.string.snapshot_meta, st.snapshotMeta),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            // F2：OptIcon 式源行——恒定 40dp IconButton 足迹，busy 原位换 spinner
            st.sources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val srcCtx = LocalContext.current
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 批T9：内置源显示名资源化（英文 locale 不再漏中文）
                            Text(
                                if (src.builtin) stringResource(R.string.source_official) else src.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (src.builtin) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.builtin),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                src.url,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.Launch,
                                contentDescription = null,
                                Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            if (src.lastFetched.isBlank()) stringResource(R.string.not_fetched)
                            else stringResource(R.string.fetched_at) + " " + fmtFetched(src.lastFetched),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        " ",
                        modifier = Modifier.clickable {
                            runCatching {
                                srcCtx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, src.url.toUri())
                                )
                            }
                        }
                    )
                    if (st.busy) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = { vm.refreshSource(src) { onMsg(it) } }, Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Sync, contentDescription = stringResource(R.string.update), Modifier.size(20.dp))
                        }
                    }
                    if (!src.builtin) {
                        IconButton(
                            onClick = { vm.removeSource(src) { onMsg(it) } },
                            Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.remove),
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { onShowAddSource() },
                enabled = !st.busy
            ) { Text(stringResource(R.string.add_source_title)) }
        }
    }

}
@Composable
private fun RecoverySection(st: AppUiState, vm: AppViewModel, onMsg: (String) -> Unit) {
    // ── 应急通道（F3：恢复点管理迁入 + 复制按钮 + 清除全部迁入） ──
    SectionTitle(stringResource(R.string.sec_recovery))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val cmd = "adb shell am broadcast -a io.github.deserthouse.prunoid.action.CLEAR_IFW --ez confirm true"
            SettingRow(
                icon = Icons.Outlined.ContentCopy,
                title = stringResource(R.string.recovery_cmd_title),
                subtitle = stringResource(R.string.recovery_cmd_sub),
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(cmd))
                    onMsg(cmd)
                }
            )
            Text(
                cmd,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.recovery_clear_title), style = MaterialTheme.typography.titleSmall)
                if (st.ifwTotal > 0) {
                    Text(
                        stringResource(R.string.recovery_ifw_count, st.ifwTotal),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.recovery_clear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                CountdownConfirmTextButton(
                    label = stringResource(R.string.clear_ifw),
                    armedLabel = stringResource(R.string.clear_ifw_confirm),
                    enabled = !st.busy,
                    onConfirm = { vm.clearAllIfw { onMsg(it) } }
                )
                Spacer(Modifier.height(6.dp))
                // 批P2#23：卸载前清规则提示（root 工具经典翻车点）
                Text(
                    stringResource(R.string.uninstall_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}
@Composable
private fun AboutSection(st: AppUiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    // ── 关于（对齐 OptIcon：整卡=彩蛋按钮；GitHub 行子消费点击） ──
    SectionTitle(stringResource(R.string.sec_about))
    Card(
        onClick = { vm.onAboutCardTapped() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f)) {
                    Text("Prunoid", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "v" + BuildConfig.VERSION_NAME + " · " + stringResource(R.string.about_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (st.easterUnlocked) {
                    Icon(
                        if (st.easterExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            // 项目主页行：子 clickable 自消费，不喂彩蛋计数
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    "https://github.com/deserthouse/Prunoid".toUri()
                                )
                            )
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.about_github),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "github.com/deserthouse/Prunoid",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.Launch,
                    contentDescription = null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // 解锁后的作者块（二级：碎碎念块 🍆×6→💦→烧断）
            androidx.compose.animation.AnimatedVisibility(
                visible = st.easterExpanded,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.avatar_deserthouse),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    stringResource(R.string.easter_author_name),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.easter_author_handle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                stringResource(R.string.easter_author_en),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.easter_vibe_line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            "https://github.com/deserthouse".toUri()
                                        )
                                    )
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "github.com/deserthouse",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Launch,
                            contentDescription = null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.onRambleTapped() }
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.easter_card_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.easter_ai_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = st.easterRambleBurned,
                            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                        ) {
                            Text(
                                stringResource(R.string.easter_extra_line),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun CreditsSection() {
    // ── 致谢（对齐 OptIcon CreditEntry：项目+描述+可点链接） ──
    SectionTitle(stringResource(R.string.about_ack_title))
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            CreditEntry(
                stringResource(R.string.credit_lcr_name),
                stringResource(R.string.credit_lcr_desc),
                "https://github.com/libchecker/LibChecker-Rules"
            )
            CreditEntry(
                stringResource(R.string.credit_bgr_name),
                stringResource(R.string.credit_bgr_desc),
                "https://github.com/lihenggui/blocker-general-rules"
            )
            CreditEntry(
                stringResource(R.string.credit_libsu_name),
                stringResource(R.string.credit_libsu_desc),
                "https://github.com/topjohnwu/libsu"
            )
        }
    }

}
@Composable
private fun ComplianceSection() {
    // ── AI 声明 / 免责（独立合规卡，不参与彩蛋） ──
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(
                icon = Icons.Outlined.SmartToy,
                title = stringResource(R.string.about_ai_title),
                subtitle = stringResource(R.string.about_ai_body),
                subtitleMaxLines = 3
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.about_share_title),
                subtitle = stringResource(R.string.about_share_body),
                subtitleMaxLines = 3
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Outlined.GppMaybe,
                title = stringResource(R.string.about_disclaimer_title),
                subtitle = stringResource(R.string.about_disclaimer_body),
                subtitleMaxLines = 3
            )
        }
    }
}
