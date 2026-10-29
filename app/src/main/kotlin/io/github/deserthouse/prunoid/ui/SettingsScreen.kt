package io.github.deserthouse.prunoid.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// 设置页（E3 对齐 OptIcon 版式）：SectionTitle + SettingsCard 分组结构。
// 键值事实源在 SettingsRepository（DataStore），此处只做读写呈现。

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var msgSeq by remember { mutableStateOf(0) }
    // 批G4：seq 参与 SnackbarEffect key——同文本连发（如两次同一失败）也能弹出（批J#4 机制此处此前漏用）
    fun show(m: String) { msg = m; msgSeq++ }
    // 批G4：加源失败消息内嵌对话框（不走 Snackbar——dialog window dim 层会遮住它）
    var addSourceError by remember { mutableStateOf<String?>(null) }
    var showAddSource by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, msg, msgSeq)

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
            // 批J1（S01）：组间距 24dp（原 12dp 与 SectionTitle top16 叠加出 28dp 大空隙）；
            // 分区经 SettingsSection 包装后整组占一个间距槽
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 批N5（F11/审查 §4-L2）：五任务组收敛——常用偏好/执行与自动化/规则数据/恢复与实验/
            // 关于（语言与关于保持原位）；危险恢复与实验同组并强化边界语义
            SettingsSection(stringResource(R.string.sec_general)) {
                WorkModeCard(st, vm)
                NotifPermCard()
            }

            // 批T3（C07 短修）：两个内容卡均仅 ROOT 渲染——非 ROOT 下整组隐藏（不再空标题占位）；
            // 完整能力分层（根页当前值+二级页）归批U。
            if (st.workMode.id.name == "ROOT") {
                SettingsSection(stringResource(R.string.sec_exec)) {
                    EnginesCard(st, vm)
                    AutomationBackupCard(st, vm, onShowBackup = { showBackup = true })
                }
            }

            SettingsSection(stringResource(R.string.sec_sub)) {
                SubscriptionsCard(st, vm, onShowAddSource = { showAddSource = true }, onMsg = { show(it) })
            }

            SettingsSection(stringResource(R.string.sec_recovery_exp)) {
                RecoveryCards(st, vm, onMsg = { show(it) })
                DeclarativeCard(st, vm, onMsg = { show(it) })
            }

            LanguageSection()

            AboutSection(st, vm)

            CreditsSection()

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showBackup) {
        BackupDialog(
            vm = vm,
            onMessage = { show(it) },
            onDismiss = { showBackup = false }
        )
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            error = addSourceError,
            onConfirm = { name, url ->
                // 批N3：失败不静默——成功才关
                // 批G4：失败消息内嵌对话框（dialog window 的 dim 层压住 activity 层 Snackbar，
                // 走 Snackbar 用户看不见——实机截图实证），错误文本直接渲染在对话框内
                addSourceError = null
                vm.addSource(name, url) { ok, msgText ->
                    if (ok) {
                        show(msgText)
                        showAddSource = false
                    } else {
                        addSourceError = msgText
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDialog(vm: AppViewModel, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    // F3：备份=可选机制。批S3（V09）：自 AlertDialog 改任务底表——十份时间戳文件/路径说明/
    // 份数编辑超出对话框承载量（长说明把控件推到末尾）；底表顶部=份数与保留摘要，
    // 列表=人可读日期主行+文件名次级，存储说明折叠进"存储详情"，恢复动作绑定所选。
    // 批R4（审计 P-6）：LaunchedEffect 异步加载（备份多时不再卡主线程）
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { backups = vm.listBackups() }
    // 批J#3：备份快照是全量状态，多选恢复语义不成立——改单选（最后选中者生效）
    var selected by remember { mutableStateOf<String?>(null) }
    // 批J2（S06）：显示与存储同口径——存量 0（未设置过）按生效值 1 呈现，不再显示 0 存 1
    var keepText by remember { mutableStateOf(vm.state.value.backupKeep.coerceAtLeast(1).toString()) }
    var confirmRestore by remember { mutableStateOf(false) }
    var storeOpen by remember { mutableStateOf(false) }
    // 批S3 lint：组合期读 StateFlow.value 禁用——collectAsState 订阅
    val bSt by vm.state.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.backup_entry_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.backup_sheet_summary,
                    backups.size, bSt.backupKeep.coerceAtLeast(1)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            // ── 保留份数（自由填写，不再滑杆）──
            var keepSaved by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.backup_keep_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = keepText,
                    onValueChange = { v ->
                        keepText = v.filter { it.isDigit() }.take(5)
                        keepSaved = false
                    },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val n = keepText.toIntOrNull()?.coerceIn(1, 99999) ?: 10
                        vm.setBackupKeep(n)
                        // 批J2（S06）：回写实际生效值——原输入 0 时提示已保存但实际存 1
                        keepText = n.toString()
                        keepSaved = true
                    },
                    enabled = keepText.toIntOrNull() != null
                ) { Text(stringResource(R.string.save)) }
                if (keepSaved) {
                    Text(
                        stringResource(R.string.backup_keep_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // ── 恢复点：人可读日期主行 + 文件名次级 ──
            Text(stringResource(R.string.backup_points_title), style = MaterialTheme.typography.titleSmall)
            if (backups.isEmpty()) {
                Text(
                    stringResource(R.string.recovery_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(4.dp))
                backups.forEach { path ->
                    val fname = path.substringAfterLast("/")
                    // backup_yyyyMMdd-HHmmss.tar.gz → "yyyy-MM-dd HH:mm:ss"
                    val human = Regex("""backup_(\d{8})-(\d{6})\.tar\.gz""").find(fname)
                        ?.let { m ->
                            val (d, t) = m.destructured
                            "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} " +
                                "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
                        }
                    // 批J2（S07）：单选语义 RadioButton 化+整行可点
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { selected = path }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == path,
                            onClick = { selected = path }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                human ?: fname,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                fname,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Button(
                    onClick = { confirmRestore = true },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(stringResource(R.string.restore_selected)) }
            }
            Spacer(Modifier.height(8.dp))
            // ── 存储详情折叠（howto/路径/说明）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { storeOpen = !storeOpen }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.backup_store_details),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = storeOpen,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column {
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
                    Text(
                        stringResource(R.string.backup_keep_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore_selected)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                // 批J2：单次确认（用户明令拆除倒计时）
                TextButton(
                    onClick = {
                        confirmRestore = false
                        vm.restoreBackup(selected!!) { onMessage(it) }
                        selected = null
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.restore), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}


@Composable
fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit, error: String? = null) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // 批N3：提交中状态（失败不静默，成功关闭）；批G4：失败态复位提交中+错误内嵌渲染
    var submitting by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(error) {
        if (error != null) submitting = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            // 批J3（S09）：滚动退路——键盘弹出/长错误时内容可滚，按钮不再被截断
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank() && !submitting) {
                        submitting = true
                        onConfirm(name.trim(), url.trim())
                    }
                },
                enabled = url.startsWith("http") && !submitting
            ) { Text(if (submitting) stringResource(R.string.adding) else stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
