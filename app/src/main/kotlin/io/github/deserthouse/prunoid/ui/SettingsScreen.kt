package io.github.deserthouse.prunoid.ui

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val snackbar = rememberSnackbar()
    var msg by remember { mutableStateOf<String?>(null) }
    var showAddSource by remember { mutableStateOf(false) }

    SnackbarEffect(snackbar, msg)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) },
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

            // ── 禁用引擎 ──
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

            // ── 自动化与备份 ──
            SectionTitle(stringResource(R.string.sec_auto))
            SettingsCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingRow(
                        icon = Icons.Outlined.Autorenew,
                        title = stringResource(R.string.auto_title),
                        subtitle = stringResource(R.string.auto_summary)
                    ) {
                        Switch(checked = st.autoReapply, onCheckedChange = { vm.setAutoReapply(it) })
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
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.backup_keep_title), style = MaterialTheme.typography.titleSmall)
                        var keepLocal by remember(st.backupKeep) { mutableStateOf(st.backupKeep.toFloat()) }
                        Text(
                            stringResource(R.string.backup_keep_current, keepLocal.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            }

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
                    st.sources.forEach { src ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(src.name, style = MaterialTheme.typography.labelLarge)
                                    if (src.builtin) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.builtin),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    src.url,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (src.lastFetched.isBlank()) stringResource(R.string.not_fetched)
                                    else stringResource(R.string.fetched_at) + " " + fmtFetched(src.lastFetched),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { vm.refreshSource(src) { msg = it } },
                                enabled = !st.busy
                            ) { Text(stringResource(R.string.update)) }
                            if (!src.builtin) {
                                TextButton(
                                    onClick = { vm.removeSource(src) { msg = it } },
                                    enabled = !st.busy
                                ) { Text(stringResource(R.string.remove)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showAddSource = true },
                        enabled = !st.busy
                    ) { Text(stringResource(R.string.add_source_title)) }
                }
            }

            // ── 应急通道 ──
            SectionTitle(stringResource(R.string.sec_recovery))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.recovery_desc),
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
                            "adb shell am broadcast -a io.github.deserthouse.prunoid.action.CLEAR_IFW --ez confirm true",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )
                    }
                }
            }

            // ── 关于 ──
            SectionTitle(stringResource(R.string.sec_about))
            SettingsCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val ctx = LocalContext.current
                    SettingRow(
                        icon = Icons.Outlined.Info,
                        title = "Prunoid",
                        subtitle = "v" + BuildConfig.VERSION_NAME + " · " + stringResource(R.string.about_line)
                    )
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.about_github),
                        subtitle = "github.com/deserthouse/Prunoid",
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        "https://github.com/deserthouse/Prunoid".toUri()
                                    )
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Outlined.Redeem,
                        title = stringResource(R.string.about_ack_title),
                        subtitle = stringResource(R.string.about_ack_body),
                        subtitleMaxLines = 3
                    )
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Outlined.SmartToy,
                        title = stringResource(R.string.about_ai_title),
                        subtitle = stringResource(R.string.about_ai_body),
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
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onConfirm = { name, url ->
                showAddSource = false
                vm.addSource(name, url) { msg = it }
            }
        )
    }
}

/** OptIcon 同款分组标题（卡片外的小节标题） */
@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
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
fun AddSourceDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(name.trim(), url.trim()) },
                enabled = url.startsWith("http")
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
