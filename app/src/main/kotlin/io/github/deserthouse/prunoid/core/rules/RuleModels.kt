package io.github.deserthouse.prunoid.core.rules

import kotlinx.serialization.Serializable

// 自建规则 schema（Thanox/Blocker 社区范本扩展，见 research/2026-09-17_数据源合规与建库原则.md §3）
// 逐条带 source/confidence 溯源；冷启动快照由 research/build_snapshot.py 生成

@Serializable
data class RuleSnapshot(
    val schemaVersion: Int,
    val generatedAt: String = "",
    val generator: String = "",
    val license: String = "",
    val stats: SnapshotStats = SnapshotStats(),
    val sdks: List<SdkRule> = emptyList()
)

@Serializable
data class SnapshotStats(
    val sdks: Int = 0,
    val blocker: Int = 0,
    val fuckad: Int = 0,
    val lcrComponents: Int = 0,
    val lcrSo: Int = 0,
    val of2pks: Int = 0
)

@Serializable
data class SdkRule(
    val id: String,
    val name: String,
    val company: String = "",
    val category: String = "other",
    val packPrefixes: List<String> = emptyList(),
    val components: List<ComponentAnchor> = emptyList(),
    val safeToBlock: Boolean = false,
    val sideEffect: String = "",
    val sources: List<String> = emptyList(),
    val confidence: String = "low",
    val contributors: List<String> = emptyList(),
    // 富描述（来源：LibChecker-Rules v4 组件规则，Apache-2.0，zh-Hans 优先）
    val description: String = "",
    val devTeam: String = "",
    val sourceLink: String = ""
)

@Serializable
data class ComponentAnchor(
    val type: String,   // activity | service | receiver | provider | native
    val `class`: String
)

enum class Safety { SAFE, CAUTION, RISKY, UNKNOWN }

fun SdkRule.safety(): Safety = when {
    safeToBlock -> Safety.SAFE
    confidence == "high" && category in setOf("ads", "analytics", "push") -> Safety.CAUTION
    category == "other" && confidence == "low" -> Safety.UNKNOWN
    else -> Safety.RISKY
}
