package io.github.deserthouse.prunoid.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkArchiveSheet(
    hit: SdkHit,
    app: ScannedApp,
    vm: AppViewModel,
    appliedEntry: AppliedRulesStore.AppliedEntry?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    libraryContext: Boolean = false,
    onDisableEverywhere: (() -> Unit)? = null
) {
    val info = remember(hit.ruleId) { vm.ruleInfo(hit.ruleId) }
    // 量化句：N matched / N blocked（blocked = 已应用记录中命中的组件数）
    val blocked = appliedEntry?.components?.count { c -> hit.matchedComponents.any { it == c } } ?: 0
    val groups = remember(hit.ruleId) {
        hit.matchedComponents.groupBy { cn ->
            hit.componentTypes[cn] ?: when {
                cn.endsWith("Activity") -> "activity"
                cn.endsWith("Service") -> "service"
                cn.endsWith("Receiver") -> "receiver"
                cn.endsWith("Provider") -> "provider"
                else -> "other"
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SdkMonogram(hit.ruleId, hit.name, Modifier.size(56.dp), vm.ruleInfo(hit.ruleId)?.iconUrl)
            Spacer(Modifier.height(8.dp))
            Text(hit.name, style = MaterialTheme.typography.titleLarge)
            // 批#11：组内英文别名展示（合并实体的另一名字）
            vm.aliasesFor(hit.name).takeIf { it.isNotBlank() }?.let { alias ->
                Text(
                    alias,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                categoryLabel(hit.category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // judge 拉通重审补：档案卡头部补四级安全徽标（详情 SDK 行有而此处缺，一致性）
            Spacer(Modifier.height(6.dp))
            SafetyBadge(hit.safety)
            Spacer(Modifier.height(8.dp))
            if (!libraryContext) {
                Text(
                    stringResource(R.string.sheet_matched_line, hit.matchedComponents.size, blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 批库链修复④：库上下文不显示锚点口径量化句（与下方扫描集清单自相矛盾）
            // 批P：全局声明开关（所有档案卡可见；写 declarations.json）
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.decl_toggle), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                val vmDecl = vm.state.collectAsState().value
                val ruleDecls = remember(hit.ruleId) {
                    vm.ruleInfo(hit.ruleId)?.packPrefixes?.map { it.trimEnd('.') }?.toSet() ?: emptySet()
                }
                val declOn = remember(vm.declared) { ruleDecls.isNotEmpty() && ruleDecls.any { it in vm.declared } }
                Switch(checked = declOn, onCheckedChange = { on ->
                    vm.toggleDeclaration(hit.ruleId, on)
                })
            }
            if (!libraryContext) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sheet_toggle), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = checked, onCheckedChange = onToggle)
                }
            } else {
                // 批M1：库上下文——使用此 SDK 的应用清单（各应用禁用状态，等待用户调整）
                Spacer(Modifier.height(12.dp))
                val st0 = vm.state.collectAsState().value
                val liveSet0 = st0.liveDisabled
                val userApps = st0.apps.filter { a -> a.matchedSdks.any { it.ruleId == hit.ruleId } }
                // 批M3：sheet 内嵌状态行（Snackbar 在 sheet 之下会被遮挡，改本地呈现）
                var sheetStatus by remember { mutableStateOf<String?>(null) }
                sheetStatus?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                    }
                }
                if (userApps.isEmpty()) {
                    Text(
                        stringResource(R.string.sheet_no_apps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.sheet_apps_title, userApps.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        // 批库链修复②：全局禁用按钮（此前 onDisableEverywhere 为死回调从未接入）
                        onDisableEverywhere?.let { cb ->
                            FilledTonalButton(
                                onClick = cb,
                                enabled = !st0.busy && st0.workMode.capabilities.disablePerApp && st0.rootGranted,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.block_everywhere, userApps.size),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    userApps.take(30).forEach { a ->
                        val disN = liveSet0[a.packageName]?.count { c ->
                            a.matchedSdks.filter { it.ruleId == hit.ruleId }.any { h ->
                                h.matchedComponents.any { it == c || (a.packageName + "/" + it) == c }
                            }
                        } ?: 0
                        val total = a.matchedSdks.first { it.ruleId == hit.ruleId }.matchedComponents.size
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label.ifEmpty { a.packageName }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (disN > 0) stringResource(R.string.sheet_app_disabled, disN, total)
                                    else stringResource(R.string.sheet_app_pending),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (disN > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
            Switch(
                // 批库链修复③：开关=该 app × 该 SDK 单粒度（不再全局/整 app 越权）
                checked = vm.sdkEnabledFor(a, hit.ruleId),
                onCheckedChange = { on ->
                    vm.setSdkForApp(a.packageName, hit.ruleId, on) { sheetStatus = it }
                },
                enabled = !st0.busy && st0.workMode.capabilities.disablePerApp && st0.rootGranted
            )
                        }
                    }
                    if (userApps.size > 30) {
                        Text(
                            stringResource(R.string.sheet_apps_more, userApps.size - 30),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            info?.description?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard(stringResource(R.string.sheet_desc_title)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }
            info?.sourceLink?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard(stringResource(R.string.sheet_links)) {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard(stringResource(R.string.sheet_safe)) {
                Text(
                    if (info?.safeToBlock == true) stringResource(R.string.yes) else stringResource(R.string.no_caution),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard(stringResource(R.string.sheet_sideeffect)) {
                Text(
                    info?.sideEffect?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && it != "未知" }
                        ?: stringResource(R.string.unknown),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            ArchiveFieldCard(stringResource(R.string.sheet_dev)) {
                Text(
                    listOf(
                        info?.devTeam?.ifBlank { info?.company }?.takeIf { it.isNotBlank() },
                        info?.confidence?.let { stringResource(R.string.conf_label, confidenceLabel(it)) }
                    ).filterNotNull().joinToString(" · ").ifBlank { stringResource(R.string.unknown) },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            info?.contributors?.takeIf { it.isNotEmpty() }?.let { c ->
                ArchiveFieldCard(stringResource(R.string.sheet_contributors)) {
                    Text(c.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            ArchiveFieldCard(stringResource(R.string.sheet_matched, hit.matchedComponents.size)) {
                Column {
                    val typeOrder = listOf("activity", "service", "receiver", "provider", "other")
                    typeOrder.forEach { t ->
                        val comps = groups[t] ?: return@forEach
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "${typeLabel(t)} × ${comps.size}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        comps.forEach { c ->
                            Text(
                                c,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
