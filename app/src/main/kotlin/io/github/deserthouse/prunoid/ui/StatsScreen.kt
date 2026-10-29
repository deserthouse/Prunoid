package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.rules.SdkRule
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

// 统计页（E2 重组）：SDK 分类统计为主角——总量卡 → 分类环形图（中央总数+图例）→ 在机排行 → 未识别前缀 Top。
// Target API 分布已撤（2026-09-19 用户拍板：非本工具焦点，AppChecker 吸收时的判断偏差）。

private data class StatsRow(val rule: SdkRule, val hitApps: Int, val hitComponents: Int)

private fun categoryColor(c: String, dark: Boolean): Color = chartColor(c, dark)

@Composable
fun StatsScreen(vm: AppViewModel, modifier: Modifier = Modifier, onOpenSdk: (String) -> Unit = {}) {
    val st by vm.state.collectAsState()
    val dark = isDark()

    val ruleById = remember(st.sources) { vm.allRules().associateBy { it.id } }
    // 聚合：每规则命中应用数/组件数
    val rows = remember(st.apps, st.sources) {
        val m = HashMap<String, IntArray>()
        st.apps.forEach { app ->
            app.matchedSdks.forEach { hit ->
                val a = m.getOrPut(hit.ruleId) { IntArray(2) }
                a[0] += 1
                a[1] += hit.matchedComponents.size
            }
        }
        m.map { (id, arr) ->
            StatsRow(ruleById[id] ?: SdkRule(id = id, name = id), arr[0], arr[1])
        }.sortedByDescending { it.hitApps }
    }
    // 分类分布（环形图主角）
    val catDist = remember(st.apps) {
        val m = LinkedHashMap<String, Int>()
        st.apps.forEach { app -> app.matchedSdks.forEach { hit -> m[hit.category] = (m[hit.category] ?: 0) + 1 } }
        m.entries.sortedByDescending { it.value }
    }
    val catTotal = catDist.sumOf { it.value }
    // 图例占比整数化（最大余数法恒和 100）
    val catPct = remember(catDist) {
        val exact = catDist.map { (c, n) -> c to n * 100.0 / catTotal.coerceAtLeast(1) }
        val floors = exact.map { (c, p) -> Triple(c, kotlin.math.floor(p).toInt(), p - kotlin.math.floor(p)) }
        val remainder = 100 - floors.sumOf { it.second }
        val bump = floors.sortedByDescending { it.third }.take(remainder.coerceAtLeast(0)).map { it.first }.toSet()
        floors.map { (c, f, _) -> c to f + if (c in bump) 1 else 0 }.toMap()
    }
    val totalBlocked = remember(st.applied) { st.applied.values.sumOf { it.components.size } }
    val totalIdentified = remember(st.apps) { st.apps.sumOf { it.matchedSdks.size } }
    // 未识别组件前缀 Top（跨 app 聚合，接规则仓贡献引导）
    val prefixTop = remember(st.apps) {
        val m = HashMap<String, Int>()
        st.apps.forEach { app -> app.unmatched.forEach { u -> m[u.prefix] = (m[u.prefix] ?: 0) + u.count } }
        m.entries.sortedByDescending { it.value }.take(5)
    }

    // 批V4（ME-01/VV-02）：统计页补 TopAppBar——标题常驻（原滚动列内 Text 滚出视口后
    // 页面失去上下文）；副标题给三量摘要，与其他根 tab 骨架同构
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.stats_summary_line, totalIdentified, st.apps.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 批V5（ME-04）：空数据态——"先扫描"引导替代零值卡堆（工具应用第一印象路径）
        if (st.apps.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.stats_empty_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.stats_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (st.apps.isNotEmpty()) {
        // 总量卡
        // 批N4（F11）：三量分明——命中次数（跨应用）/独立 SDK/已管组件（按账本）；
        // 扫描应用数降为卡片脚注（原"在机识别 SDK"实为命中次数，与库页独立口径混淆）
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    // 批遗留#2：三列顶部对齐——标签两行的列不再把大数字居中抬高
                    verticalAlignment = Alignment.Top
                ) {
                    val distinctSdks = remember(st.apps) {
                        st.apps.flatMap { a -> a.matchedSdks.map { it.ruleId } }.distinct().size
                    }
                    listOf(
                        stringResource(R.string.stats_identified) to "$totalIdentified",
                        stringResource(R.string.stats_distinct) to "$distinctSdks",
                        stringResource(R.string.stats_blocked) to "$totalBlocked"
                    ).forEach { (label, value) ->
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top
                        ) {
                            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.stats_apps, st.apps.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 分类分布（批S2/V08）：条形行替代环图+图例——148dp 环图+独立图例的纵向成本
        // 减半，每类"计数·占比+比例条"直接可读；不靠颜色单独承载数值。
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_cat_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.stats_cat_total_line, catTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                // 批U4（C10）：Top N+其余合计——类目线性增长的高度成本有界（报告 C10）
                var showAllCats by remember { mutableStateOf(false) }
                val visibleCats = if (showAllCats || catDist.size <= 8) catDist else catDist.take(8)
                val restCount = catDist.size - visibleCats.size
                val restSum = catDist.drop(visibleCats.size).sumOf { it.value }
                visibleCats.forEach { (cat, n) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(categoryColor(cat, dark), RoundedCornerShape(50)))
                            Spacer(Modifier.width(6.dp))
                            Text(categoryLabel(cat), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "$n · ${catPct[cat] ?: 0}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        // 批V5（VV-03）：满宽底轨提供 100% 参照（surfaceVariant 高 6dp 同圆角）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(n.toFloat() / catTotal.coerceAtLeast(1))
                                    .height(6.dp)
                                    .background(categoryColor(cat, dark), RoundedCornerShape(50))
                            )
                        }
                    }
                }
                if (restCount > 0) {
                    TextButton(onClick = { showAllCats = true }) {
                        Text(
                            stringResource(R.string.stats_cats_more, restCount, restSum),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        // 在机 SDK 排行（批V5：空数据不渲染空壳卡）
        if (rows.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_rank_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                rows.take(10).forEachIndexed { i, row ->
                    Row(
                        // 批CC4：排行进入动作——点行跳 SDK 库并带入搜索词
                        // 批FF-B4（方案B S2c/D05）：改传 ruleId——同名实体下 name 查询有歧义
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpenSdk(row.rule.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(row.rule.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                pluralStringResource(R.plurals.badge_apps, row.hitApps, row.hitApps),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        // 未识别前缀 Top（跨 app 聚合；引导提交规则仓）
        if (prefixTop.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.stats_prefix_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.stats_prefix_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    prefixTop.forEach { (prefix, n) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                prefix,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                stringResource(R.string.stats_prefix_count, n),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        }
    }
    }
}
// 批S2（V08）：DonutChart/LegendList 随环图方案退役——分类分布由条形行直接承载
