package io.github.deserthouse.prunoid.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

            SubscriptionsSection(st, vm, onShowAddSource = { showAddSource = true }, onMsg = { show(it) })

            RecoverySection(st, vm, onMsg = { show(it) })

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
