package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.rules.safety
import io.github.deserthouse.prunoid.core.rules.SdkRule
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit

// SDK 库浏览页（Blocker Found/Not found 模式，#7）：
// 全量规则库按stringResource(R.string.lib_subtitle)两段浏览；点入 = SDK 档案卡弹层。
// 数据 = 内置快照 + 订阅合并后的全量规则（vm.allRules），命中计数来自最近一次扫描。

private data class LibRow(
    val rule: SdkRule,
    val hitApps: Int,
    val hitComponents: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkLibraryScreen(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val snackbar = rememberSnackbar()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }   // 0 = 在机检出, 1 = 未检出
    var sheetRow by remember { mutableStateOf<LibRow?>(null) }
    var sheetMsg by remember { mutableStateOf<String?>(null) }

    // ruleId → (命中 app 数, 命中组件数)。口径=在机检出（全量 apps，与统计页排行一致）；
    // 白名单系统应用的排除由禁用引擎在执行层兜底，展示层不重复过滤——
    // 曾因 eligible 过滤出现库徽标 17 vs 档案卡/统计页 74 的同 SDK 三处口径不一（judge 抓出）
    val hitMap = remember(st.apps) {
        val m = HashMap<String, IntArray>()
        st.apps.forEach { app ->
            app.matchedSdks.forEach { hit ->
                val a = m.getOrPut(hit.ruleId) { IntArray(2) }
                a[0] += 1
                a[1] += hit.matchedComponents.size
            }
        }
        m
    }
    val all = remember(st.sources) { vm.allRules() }
    val foundCount = remember(all, hitMap) { all.count { (hitMap[it.id] ?: IntArray(2))[0] > 0 } }

    SnackbarEffect(snackbar, st.message)
    SnackbarEffect(snackbar, sheetMsg)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (st.scanning) stringResource(R.string.lib_scanning)
                            else stringResource(R.string.lib_summary, foundCount, all.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text(stringResource(R.string.lib_tab_found, foundCount)) }
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text(stringResource(R.string.lib_tab_missing, all.size - foundCount)) }
                )
            }
            // 批S2：筛选/排序与列表页同构——Filter（sheet）+ Sort（menu，带当前项勾选）
            var libCat by remember { mutableStateOf(setOf<String>()) }
            var libSortByName by remember { mutableStateOf(false) }
            var libFilterSheet by remember { mutableStateOf(false) }
            var libSortMenu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = { libFilterSheet = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (libCat.isNotEmpty()) stringResource(R.string.filter_label_n, libCat.size)
                         else stringResource(R.string.filter_label))
                }
                Box(Modifier.weight(1f)) {
                    FilledTonalButton(onClick = { libSortMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (libSortByName) R.string.sort_name else R.string.sort_default),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = libSortMenu, onDismissRequest = { libSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_default)) },
                            trailingIcon = { if (!libSortByName) Icon(Icons.Outlined.Check, contentDescription = null) },
                            onClick = { libSortByName = false; libSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_name)) },
                            trailingIcon = { if (libSortByName) Icon(Icons.Outlined.Check, contentDescription = null) },
                            onClick = { libSortByName = true; libSortMenu = false }
                        )
                    }
                }
            }
            if (libFilterSheet) {
                ModalBottomSheet(onDismissRequest = { libFilterSheet = false }) {
                    Column(
                        Modifier.padding(horizontal = 20.dp).navigationBarsPadding()
                            .padding(bottom = 12.dp).verticalScroll(rememberScrollState())
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.filter_label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { libCat = emptySet() }) { Text(stringResource(R.string.filter_clear)) }
                        }
                        SectionLabel(stringResource(R.string.filter_category))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            ALL_CATEGORIES.forEach { c ->
                                FilterChip(
                                    selected = c in libCat,
                                    onClick = { libCat = if (c in libCat) libCat - c else libCat + c },
                                    label = { Text(categoryLabel(c)) }
                                )
                            }
                        }
                    }
                }
            }
            val rows = remember(all, hitMap, tab, query, libCat, libSortByName) {
                all.map { r ->
                    val h = hitMap[r.id] ?: IntArray(2)
                    LibRow(r, h[0], h[1])
                }
                    .filter { if (tab == 0) it.hitApps > 0 else it.hitApps == 0 }
                    .filter { libCat.isEmpty() || it.rule.category in libCat }
                    .filter {
                        query.isBlank() || it.rule.name.contains(query, true) ||
                            it.rule.company.contains(query, true)
                    }
                    .let { l ->
                        if (libSortByName) l.sortedBy { it.rule.name.lowercase() }
                        else l.sortedWith(compareByDescending<LibRow> { it.hitApps }.thenBy { it.rule.name })
                    }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.lib_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(rows, key = { it.rule.id }) { row ->
                        ListItem(
                            headlineContent = {
                                Text(row.rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        (row.rule.company.ifBlank { categoryLabel(row.rule.category) }) +
                                            " · " + categoryLabel(row.rule.category),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // 批T5：Not found 页不显风险徽标（不在机谈不到禁用风险）
                                    if (tab == 0) SafetyBadge(row.rule.safety())
                                }
                            },
                            leadingContent = {
                                SdkMonogram(row.rule.id, row.rule.name)
                            },
                            trailingContent = {
                                // 批U2：计数去胶囊降密度（语义色留给安全徽标）
                                if (row.hitApps > 0) {
                                    Text(
                                        pluralStringResource(R.plurals.badge_apps, row.hitApps, row.hitApps),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                // 构造档案卡所需的 SdkHit 视图（锚点即全量组件类名）
                                sheetRow = row
                            }
                        )
                    }
                }
            }
        }
    }

    sheetRow?.let { row ->
        SdkArchiveSheet(
            hit = SdkHit(
                ruleId = row.rule.id,
                name = row.rule.name,
                category = row.rule.category,
                // 库页无 app 上下文，四级安全派生与详情页一致（R1-P1⑦ 补修）
                safety = row.rule.safety(),
                matchedComponents = row.rule.components.map { it.`class` },
                componentTypes = row.rule.components.associate { it.`class` to it.type }
            ),
            libraryContext = true,
            onDisableEverywhere = {
                vm.disableSdkEverywhere(row.rule.id) { sheetMsg = it }
            },
            app = ScannedApp(
                packageName = "", label = "", isSystem = false, matchedSdks = emptyList()
            ),
            vm = vm,
            appliedEntry = null,
            checked = false,
            onToggle = { },
            onDismiss = { sheetRow = null }
        )
    }
}
