package io.github.deserthouse.prunoid.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
fun StatsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 总量卡
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                // 批遗留#2：三列顶部对齐——标签两行的列不再把大数字居中抬高
                verticalAlignment = Alignment.Top
            ) {
                listOf(
                    stringResource(R.string.stats_identified) to "$totalIdentified",
                    stringResource(R.string.stats_blocked) to "$totalBlocked",
                    stringResource(R.string.stats_apps) to "${st.apps.size}"
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
        }
        // 分类环形图（SDK 分类统计为主角）
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_cat_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
                        // M3E：入场弧线扫入（克制单段 tween）
                        val sweepIn by animateFloatAsState(
                            targetValue = if (catTotal > 0) 1f else 0f,
                            animationSpec = tween(600, easing = FastOutSlowInEasing),
                            label = "donutSweep"
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 26.dp.toPx()
                            val inset = stroke / 2
                            val arcSize = Size(size.width - stroke, size.height - stroke)
                            var start = -90f
                            if (catTotal > 0) {
                                catDist.forEach { (cat, n) ->
                                    val sweep = n.toFloat() / catTotal * 360f * sweepIn
                                    drawArc(
                                        color = categoryColor(cat, dark),
                                        startAngle = start,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        topLeft = Offset(inset, inset),
                                        size = arcSize,
                                        style = Stroke(width = stroke)
                                    )
                                    start += sweep
                                }
                            } else {
                                drawArc(
                                    color = Color.Gray.copy(alpha = 0.2f),
                                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                    topLeft = Offset(inset, inset), size = arcSize,
                                    style = Stroke(width = stroke)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$catTotal", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.stats_cat_center),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    // 图例
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        catDist.forEach { (cat, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(categoryColor(cat, dark), RoundedCornerShape(50)))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    categoryLabel(cat),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "$n · ${catPct[cat] ?: 0}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        // 在机 SDK 排行
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_rank_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                rows.take(10).forEachIndexed { i, row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
