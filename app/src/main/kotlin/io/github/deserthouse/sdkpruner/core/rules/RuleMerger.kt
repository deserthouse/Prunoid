package io.github.deserthouse.sdkpruner.core.rules

// 规则合并纯函数：订阅源同 id 覆盖内置，新 id 追加（保持内置顺序在前）。
// 展示类富字段（description/devTeam/sourceLink）例外：订阅副本为空时保留内置值，
// 避免旧订阅快照把内置富化数据抹掉。
object RuleMerger {
    private fun SdkRule.enrichedFrom(other: SdkRule): SdkRule =
        copy(
            description = description.ifBlank { other.description },
            devTeam = devTeam.ifBlank { other.devTeam },
            sourceLink = sourceLink.ifBlank { other.sourceLink }
        )

    fun merge(builtIn: List<SdkRule>, subscribed: List<SdkRule>): List<SdkRule> {
        if (subscribed.isEmpty()) return builtIn
        val subById = subscribed.associateBy { it.id }
        val builtInById = builtIn.associateBy { it.id }
        val builtInIds = builtIn.mapTo(HashSet()) { it.id }
        return builtIn.map { rule ->
            subById[rule.id]?.enrichedFrom(rule) ?: rule
        } + subscribed.filter { it.id !in builtInIds }
            .map { it.enrichedFrom(builtInById[it.id] ?: SdkRule(id = it.id, name = it.name)) }
    }
}
