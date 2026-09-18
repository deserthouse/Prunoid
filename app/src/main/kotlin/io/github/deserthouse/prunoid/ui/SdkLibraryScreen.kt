package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    val hitComponents: Int
)

@Composable
fun SdkLibraryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val snackbar = rememberSnackbar()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }   // 0 = 在机检出, 1 = 未检出
    var sheetRule by remember { mutableStateOf<SdkHit?>(null) }

    // ruleId → (命中 app 数, 命中组件数)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (st.scanning) stringResource(R.string.lib_scanning)
                            else stringResource(R.string.lib_summary, foundCount, all.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_search))
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
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
            // E1 查找力批：分类筛选 + 排序
            var libCat by remember { mutableStateOf(setOf<String>()) }
            var libSortByName by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ads", "push", "analytics", "quality", "social_or_pay", "maps", "infra", "security", "framework", "other").forEach { c ->
                    FilterChip(
                        selected = c in libCat,
                        onClick = { libCat = if (c in libCat) libCat - c else libCat + c },
                        label = { Text(categoryLabel(c)) }
                    )
                }
                FilterChip(
                    selected = libSortByName,
                    onClick = { libSortByName = !libSortByName },
                    label = { Text(stringResource(R.string.sort_name)) }
                )
                if (libCat.isNotEmpty() || libSortByName) {
                    TextButton(onClick = { libCat = emptySet(); libSortByName = false }) {
                        Text(stringResource(R.string.filter_clear))
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
                                Text(
                                    (row.rule.company.ifBlank { categoryLabel(row.rule.category) }) +
                                        " · " + categoryLabel(row.rule.category),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                SdkMonogram(row.rule.id, row.rule.name)
                            },
                            trailingContent = {
                                if (row.hitApps > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            "${row.hitApps} app",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                // 构造档案卡所需的 SdkHit 视图（锚点即全量组件类名）
                                sheetRule = SdkHit(
                                    ruleId = row.rule.id,
                                    name = row.rule.name,
                                    category = row.rule.category,
                                    // 库页无 app 上下文，四级安全派生与详情页一致（R1-P1⑦ 补修）
                                    safety = row.rule.safety(),
                                    matchedComponents = row.rule.components.map { it.`class` },
                                    componentTypes = row.rule.components.associate { it.`class` to it.type }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    sheetRule?.let { pseudoHit ->
        SdkArchiveSheet(
            hit = pseudoHit,
            app = ScannedApp(
                packageName = "", label = "", isSystem = false, matchedSdks = emptyList()
            ),
            vm = vm,
            appliedEntry = null,
            checked = false,
            onToggle = { },
            onDismiss = { sheetRule = null }
        )
    }
}
