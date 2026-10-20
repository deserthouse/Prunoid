package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.ui.operations.OperationKind

/** 批FF-B3（AA3/方案B S2b）：SDK 跨应用管理目的地——
 *  从档案弹层抽出的完整管理面：全量应用清单（可检索、无上限）、逐应用开关、
 *  批量禁用与逐项结果（Operation ruleId 键控，AV01 语义由协议层保证）。
 *  档案弹层回归纯阅读（AA3 阅读与管理的分工）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkManageScreen(
    vm: AppViewModel,
    ruleId: String,
    onBack: () -> Unit,
) {
    val st by vm.state.collectAsState()
    val snackbar = rememberSnackbar()
    val ruleName = remember(ruleId) { vm.allRules().firstOrNull { it.id == ruleId }?.name ?: ruleId }
    // 批HH3（S4）：topBar 副标题计数 remember 化——避免每次重组全表扫
    val matchedCount = remember(ruleId, st.apps) { userAppsCount(st, ruleId) }
    // 状态声明提升至 Scaffold 外——确认对话框在组合顶层需要引用（作用域贯通）
    val liveSet0 = st.liveDisabled
    val userApps = st.apps.filter { a -> a.matchedSdks.any { it.ruleId == ruleId } }
    var appQuery by remember(ruleId) { mutableStateOf("") }
    val filteredApps = if (appQuery.isBlank()) userApps
    else userApps.filter { a ->
        a.label.contains(appQuery, true) || a.packageName.contains(appQuery, true)
    }
    // 批FF-B3：批量结果当前层呈现，按 ruleId 键控（FF1 语义）
    var bulkMsg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var bulkMsgSeq by remember { mutableStateOf(0) }
    var confirmEverywhere by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ruleName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.manage_subtitle, matchedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SnackbarEffect(snackbar, bulkMsg?.second, bulkMsgSeq)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.sheet_apps_title, userApps.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { confirmEverywhere = true },
                    enabled = userApps.isNotEmpty() && !st.busy && st.workMode.capabilities.disablePerApp && st.rootGranted,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.block_everywhere, userApps.size), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (userApps.size > 5) {
                SearchField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    placeholder = { Text(stringResource(R.string.search_app_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
            bulkMsg?.let { (_, m) ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(m, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // 批HH2（S3）：行模型派生化——key 掺入 disN/triEnabled，live 变化必触发行重组
                //（g_09 发现：批量后 KOOK 行刷新而 Oopz/CoolApk 滞后，根因=key 不含行状态）
                val rowModels = filteredApps.map { a ->
                    val hit = a.matchedSdks.firstOrNull { it.ruleId == ruleId }
                    val tri = vm.sdkEnabledFor(a, ruleId)
                    val total = hit?.matchedComponents?.size ?: 0
                    val disN = liveSet0[a.packageName]?.count { c ->
                        hit?.matchedComponents?.any { it == c || (a.packageName + "/" + it) == c } == true
                    } ?: 0
                    ManageRow(a, tri, total, disN)
                }
                items(rowModels, key = { it.app.packageName + ":" + it.disN + ":" + (it.tri == true) }) { row ->
                    val a = row.app
                    ListItem(
                        headlineContent = {
                            Text(a.label.ifEmpty { a.packageName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                if (row.disN > 0) stringResource(R.string.sheet_app_disabled, row.disN, row.total)
                                else stringResource(R.string.sheet_app_pending),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.disN > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                // EE5 三态 + EE3 当前层反馈（管理面同契约）
                                checked = row.tri == true,
                                onCheckedChange = { on ->
                                    vm.setSdkForApp(a.packageName, ruleId, on) { stMsg ->
                                        bulkMsg = ruleId to stMsg
                                        bulkMsgSeq++
                                    }
                                },
                                enabled = row.tri != null && !st.busy && st.workMode.capabilities.disablePerApp && st.rootGranted
                            )
                        }
                    )
                }
            }
        }
    }
    if (confirmEverywhere) {
        AlertDialog(
            onDismissRequest = { confirmEverywhere = false },
            title = { Text(stringResource(R.string.block_everywhere, userApps.size)) },
            text = { Text(stringResource(R.string.block_everywhere_confirm, userApps.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEverywhere = false
                        vm.disableSdkEverywhere(ruleId) {
                            bulkMsg = ruleId to it
                            bulkMsgSeq++
                        }
                    }
                ) { Text(stringResource(R.string.risk_continue), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEverywhere = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun userAppsCount(st: io.github.deserthouse.prunoid.ui.AppUiState, ruleId: String): Int =
    st.apps.count { a -> a.matchedSdks.any { it.ruleId == ruleId } }

/** 批HH2（S3）：管理页行模型——pkg+三态+计数派生后固化，key 掺状态防 LazyColumn 跳过重组 */
private data class ManageRow(
    val app: io.github.deserthouse.prunoid.core.scanner.ScannedApp,
    val tri: Boolean?,
    val total: Int,
    val disN: Int,
)
