package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.DisableEngine
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
    // 批M5（F04）：查询由根路由按 ruleId 隔离持有（管理往返不丢；分支内 remember 会随销毁丢）
    query: String = "",
    onQueryChange: (String) -> Unit = {},
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
    val appQuery = query
    val filteredApps = if (appQuery.isBlank()) userApps
    else userApps.filter { a ->
        a.label.contains(appQuery, true) || a.packageName.contains(appQuery, true)
    }
    // 批M3（F02）：批量动作消费不可变执行计划——按钮/确认/执行同源（原确认用扫描命中数，
    // 与 disableSdkEverywhere 内部过滤不同源；provider-only 走到确认后才报零目标）
    var bulkAllScope by remember { mutableStateOf(true) }
    val plan = remember(ruleId, st.apps, st.engine, appQuery, bulkAllScope, vm.rulesGeneration) {
        val src = if (bulkAllScope) userApps else filteredApps
        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner
            .planRuleAction(ruleId, st.engine, src, vm.rulesGeneration)
    }
    // 批FF-B3：批量结果当前层呈现，按 ruleId 键控（FF1 语义）
    // 批I1（F2）：结果行改消费 OperationCenter——逐 app 条目（协议贯通，bulkMsg 字符串退役）
    var confirmEverywhere by remember { mutableStateOf(false) }
    // 批I1（F2）：结果行消费 OperationCenter；per-app 开关反馈走行内状态（非 Composable 回调不可直接调 Composable）
    var rowStatus by remember { mutableStateOf<String?>(null) }
    val bulkOperation by vm.opCenter.operations.collectAsState()
    val bulkOp = bulkOperation.lastOrNull {
        it.kind == OperationKind.DISABLE_EVERYWHERE && it.target == ruleId
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ruleName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // 批M3（F02）：副标题改计划口径——命中/可操作/跳过三分（原仅扫描命中数）
                        Text(
                            stringResource(R.string.manage_plan_summary, plan.hits, plan.eligibleCount, plan.skipped.size),
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

            // 批M3（F02）：批量作用域显式化——筛选激活时可选"全部命中/当前筛选"（原隐式全量）
            if (appQuery.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = bulkAllScope,
                        onClick = { bulkAllScope = true },
                        label = { Text(stringResource(R.string.bulk_scope_all, userApps.size)) }
                    )
                    FilterChip(
                        selected = !bulkAllScope,
                        onClick = { bulkAllScope = false },
                        label = { Text(stringResource(R.string.bulk_scope_filtered, filteredApps.size)) }
                    )
                }
            }
            // 批R2（V04）：计划摘要卡——"范围 · 组件数 · 引擎"绑定动作，应用总数降为弱背景；
            // 原标题行+按钮散排无容器语义，计划与清单混在一行
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.sheet_apps_title, userApps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(
                                R.string.manage_plan_line,
                                stringResource(if (bulkAllScope) R.string.bulk_scope_all else R.string.bulk_scope_filtered, plan.hits),
                                plan.totalComponents,
                                io.github.deserthouse.prunoid.ui.engineLabel(plan.engine)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.manage_plan_summary, plan.hits, plan.eligibleCount, plan.skipped.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (plan.hits > 0 && plan.eligibleCount == 0) {
                            // 批M3（F02）：零可操作目标给原因，不留禁用死按钮
                            Text(
                                stringResource(R.string.manage_zero_plan),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        FilledTonalButton(
                            onClick = { confirmEverywhere = true },
                            // 批M3（F02）：按钮计数=执行口径（可操作数）；零可操作目标禁用（原因就地呈现）
                            enabled = plan.eligibleCount > 0 && !st.busy && st.workMode.capabilities.disablePerApp && st.rootGranted,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                stringResource(R.string.block_everywhere, plan.eligibleCount),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            if (userApps.size > 5) {
                SearchField(
                    value = appQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_app_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
            // 批I1（F2）：结果行消费 Operation——outcome 标签+逐 app 明细（部分失败可见可重试）
            // 批I2（F8）：结果行移入 LazyColumn stickyHeader——长清单滚动后结果仍在视口
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                bulkOp?.let { op ->
                    stickyHeader(key = "bulk-result") {
                        Surface(
                            color = when (op.outcome) {
                                io.github.deserthouse.prunoid.ui.operations.OperationOutcome.FAILED -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (op.outcome == io.github.deserthouse.prunoid.ui.operations.OperationOutcome.FAILED)
                                MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(op.message, style = MaterialTheme.typography.labelMedium)
                                op.items.filter { !it.ok }.take(3).forEach { f ->
                                    Text(
                                        "✗ " + f.target + (f.detail.takeIf { it.isNotBlank() }?.let { d -> " — $d" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                rowStatus?.let { stMsg ->
                    item(key = "row-status") {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stMsg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
                // 批HH2（S3）：行模型派生化——key 掺入 disN/triEnabled，live 变化必触发行重组
                //（g_09 发现：批量后 KOOK 行刷新而 Oopz/CoolApk 滞后，根因=key 不含行状态）
                val rowModels = filteredApps.map { a ->
                    val hit = a.matchedSdks.firstOrNull { it.ruleId == ruleId }
                    val tri = vm.sdkEnabledFor(a, ruleId)
                    val total = hit?.matchedComponents?.size ?: 0
                    val disN = liveSet0[a.packageName]?.count { c ->
                        hit?.matchedComponents?.any { it == c || (a.packageName + "/" + it) == c } == true
                    } ?: 0
                    // 批M4（F03）：现场四态分类（替代开关 any 单点表意——43/51 部分禁用曾亮 On 被误读全关）
                    val live = io.github.deserthouse.prunoid.core.engine.RuleActionPlanner
                        .classifyRowLive(total, disN, loaded = liveSet0.containsKey(a.packageName) && tri != null)
                    ManageRow(a, tri, total, disN, live.live)
                }
                items(rowModels, key = { it.app.packageName + ":" + it.disN + ":" + (it.tri == true) }) { row ->
                    val a = row.app
                    val protectedTarget = a.isSystem || DisableEngine.isForbidden(a.packageName)
                    // 批I2（F9）：行密度对齐主列表（卡片式两层布局）；批I2（F3/V1）：null 态行尾"未加载"辅助标识
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(MaterialTheme.shapes.small),
                        colors = ListItemDefaults.colors(
                            containerColor = if (row.live == io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.FULL)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        headlineContent = {
                            Text(a.label.ifEmpty { a.packageName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column {
                                // 批M4（F03）：状态词四态化——部分禁用显式可见（不再并入 On 的笼统读数）
                                Text(
                                    when (row.live) {
                                        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.UNKNOWN ->
                                            stringResource(R.string.manage_not_loaded)
                                        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.NONE ->
                                            stringResource(R.string.sheet_app_pending)
                                        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.PARTIAL ->
                                            stringResource(R.string.sheet_app_disabled, row.disN, row.total) + " · " +
                                                stringResource(R.string.row_live_partial)
                                        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.FULL ->
                                            stringResource(R.string.sheet_app_disabled, row.disN, row.total) + " · " +
                                                stringResource(R.string.row_live_full)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.disN > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (protectedTarget) {
                                    Text(
                                        stringResource(R.string.manage_protected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            // 批N2（F03 残余）：开关动作语义显式化——现场状态已由行主体承载
                            //（M4 四态），语义层点明本次点击将做什么（应用/恢复本工具规则）
                            val actionApply = stringResource(R.string.manage_action_apply)
                            val actionRestore = stringResource(R.string.manage_action_restore)
                            Switch(
                                // EE5 三态 + EE3 当前层反馈（管理面同契约）
                                checked = row.tri == true,
                                onCheckedChange = { on ->
                                    vm.setSdkForApp(a.packageName, ruleId, on) { stMsg ->
                                        rowStatus = stMsg.takeIf { it.isNotBlank() }
                                    }
                                },
                                enabled = !protectedTarget && row.tri != null && !st.busy && st.workMode.capabilities.disablePerApp && st.rootGranted,
                                modifier = Modifier.semantics {
                                    // 批Q2（B02）：读屏描述=真实下一动作——按开关当前值（tri，点击分派
                                    // 的唯一依据）推导；原按 live==FULL 判定致 PARTIAL+On 描述"应用"
                                    // 而实际分派恢复。现场四态仍由行主体文字独立承载。
                                    contentDescription =
                                        if (row.tri == true) actionRestore else actionApply
                                }
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
            // 批M3（F02）：确认消费同一份计划快照——可执行应用/组件数与跳过理由逐类呈现
            //（与按钮同源，执行再经引擎安全层二次复核）
            title = { Text(stringResource(R.string.block_everywhere, plan.eligibleCount)) },
            text = {
                Text(
                    stringResource(
                        R.string.block_everywhere_confirm_plan,
                        plan.eligibleCount, plan.totalComponents, plan.engine.name,
                        plan.skipped.size,
                        plan.skipCount(io.github.deserthouse.prunoid.core.engine.SkipReason.PROTECTED),
                        plan.skipCount(io.github.deserthouse.prunoid.core.engine.SkipReason.SYSTEM),
                        plan.skipCount(io.github.deserthouse.prunoid.core.engine.SkipReason.NO_WRITABLE_COMPONENTS),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEverywhere = false
                        // 批Q1（B01）：把确认所见的那份计划冻结传给执行（原只传 ruleId，VM 全量重算
                        // 致"当前筛选"扩回全部命中、PM 计划暗走 IFW）。执行回执经 OperationCenter
                        // 呈现（executed→""，不与结果行重复）；前置拒绝（根/账本/代次过期/零目标）
                        // 返回原因文本 → rowStatus 就地展示（批Q4/B04）。
                        vm.disableSdkEverywhere(plan) { msg ->
                            rowStatus = msg.takeIf { it.isNotBlank() }
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

/** 批HH2（S3）：管理页行模型——pkg+三态+计数派生后固化，key 掺状态防 LazyColumn 跳过重组；
 *  批M4（F03）：live 四态（UNKNOWN/NONE/PARTIAL/FULL）进模型 */
private data class ManageRow(
    val app: io.github.deserthouse.prunoid.core.scanner.ScannedApp,
    val tri: Boolean?,
    val total: Int,
    val disN: Int,
    val live: io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive =
        io.github.deserthouse.prunoid.core.engine.RuleActionPlanner.RowLive.UNKNOWN,
)
