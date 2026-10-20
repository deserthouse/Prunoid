package io.github.deserthouse.prunoid.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.core.net.toUri
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import io.github.deserthouse.prunoid.ui.SearchField

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
    // 批FF3（七审 AV04）：库上下文的在机扫描命中数（跨应用去重）——锚点数不再冒充现场命中数
    scanHitCount: Int? = null,
    // 批FF-B3（AA3）：管理职责移至独立目的地——档案弹层回归阅读，此回调打开管理页
    onOpenManage: (() -> Unit)? = null
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
            val vmDecl = vm.state.collectAsState().value
            var declFail by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.decl_toggle), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val ruleDecls = remember(hit.ruleId) {
                        vm.ruleInfo(hit.ruleId)?.packPrefixes?.map { it.trimEnd('.') }?.toSet() ?: emptySet()
                    }
                    // 批R4（审计 P-7）：改读 UiState——原 remember(vm.declared) 的 key 是静态存储读数，永不触发重组，开关不回显
                    val declOn = ruleDecls.isNotEmpty() && ruleDecls.any { it in vmDecl.declared }
                    // 批S2（审计 N-2）：无 root 禁用+原因行（原可切但写失败静默零反馈）；
                    // 有 root 失败也内嵌提示（Snackbar 被 sheet 遮挡，批M3 教训）
                    Switch(
                        checked = declOn,
                        enabled = vmDecl.rootGranted && vmDecl.workMode.id.name == "ROOT",
                        onCheckedChange = { on ->
                            declFail = false
                            vm.toggleDeclaration(hit.ruleId, on) { err -> if (err.isNotBlank()) declFail = true }
                        }
                    )
                }
                if (!vmDecl.rootGranted) {
                    Text(
                        stringResource(R.string.decl_need_root),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (declFail) {
                    Text(
                        stringResource(R.string.decl_write_fail),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
                // 批FF-B3（AA3）：管理职责移至独立目的地——档案弹层回归阅读 + 管理入口行
                Spacer(Modifier.height(12.dp))
                val st0 = vm.state.collectAsState().value
                val userApps = st0.apps.filter { a -> a.matchedSdks.any { it.ruleId == hit.ruleId } }
                Button(
                    onClick = { onOpenManage?.invoke() },
                    enabled = onOpenManage != null && userApps.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.manage_open, userApps.size))
                }
            }
            Spacer(Modifier.height(12.dp))
            info?.description?.takeIf { it.isNotBlank() }?.let {
                ArchiveFieldCard(stringResource(R.string.sheet_desc_title)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }
            info?.sourceLink?.takeIf { it.isNotBlank() }?.let { link ->
                // 批R5（审计 P-11）：sourceLink 改可点（原 tertiary 色形似链接却只读不可点；CreditEntry 同款触达）
                val ctx = androidx.compose.ui.platform.LocalContext.current
                ArchiveFieldCard(stringResource(R.string.sheet_links)) {
                    Text(
                        link,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, link.toUri())
                                    )
                                }
                            }
                            .padding(vertical = 2.dp)
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
            ArchiveFieldCard(
                // 批FF3（七审 AV04）：库上下文区分两口径——在机扫描命中 vs 规则显式锚点
                if (scanHitCount != null) stringResource(R.string.sheet_matched_scan, scanHitCount, hit.matchedComponents.size)
                else stringResource(R.string.sheet_matched, hit.matchedComponents.size)
            ) {
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
