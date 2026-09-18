package io.github.deserthouse.prunoid.ui

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.rules.SdkRule

// 统计页（批 2 实装）：在机 SDK 排行榜 / 分类分布 / 禁用与识别总量。
// 数据全部来自现有扫描结果聚合，零额外扫描成本。

private data class StatsRow(val rule: SdkRule, val hitApps: Int, val hitComponents: Int)

private fun categoryColor(c: String, dark: Boolean): Color = when (c) {
    "ads" -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    "push" -> if (dark) Color(0xFFFFCC80) else Color(0xFFE65100)
    "analytics" -> if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    "framework" -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
    else -> if (dark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)
}

@Composable
fun StatsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val st by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()

    // ruleId → 规则对象
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
    // 分类分布
    val catDist = remember(st.apps) {
        val m = HashMap<String, Int>()
        st.apps.forEach { app -> app.matchedSdks.forEach { hit -> m[hit.category] = (m[hit.category] ?: 0) + 1 } }
        m
    }
    val totalBlocked = remember(st.applied) { st.applied.values.sumOf { it.components.size } }
    val totalIdentified = remember(st.apps) { st.apps.sumOf { it.matchedSdks.size } }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 总量卡
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    stringResource(R.string.stats_identified) to "$totalIdentified",
                    stringResource(R.string.stats_blocked) to "$totalBlocked",
                    stringResource(R.string.stats_apps) to "${st.apps.size}"
                ).forEach { (label, value) ->
                    // weight 均分三格，长标签（已禁用组件）与相邻格保持间距不粘连
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        // 分类分布
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_cat_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                catDist.entries.sortedByDescending { it.value }.forEach { (cat, n) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(8.dp).background(categoryColor(cat, dark), RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(categoryLabel(cat), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("$n", style = MaterialTheme.typography.labelMedium)
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
                                "${row.hitApps} app",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
