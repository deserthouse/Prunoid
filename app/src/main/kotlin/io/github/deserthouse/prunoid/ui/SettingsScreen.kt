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
                // 批N3：失败不静默——成功才关（批G1：ok 位显式传递，不再解析消息文本）
                vm.addSource(name, url) { ok, msgText ->
                    msg = msgText
                    if (ok) showAddSource = false
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
