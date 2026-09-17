package io.github.deserthouse.sdkpruner.core.rules

// 规则合并纯函数：订阅源同 id 覆盖内置，新 id 追加（保持内置顺序在前）
object RuleMerger {
    fun merge(builtIn: List<SdkRule>, subscribed: List<SdkRule>): List<SdkRule> {
        if (subscribed.isEmpty()) return builtIn
        val subById = subscribed.associateBy { it.id }
        val builtInIds = builtIn.mapTo(HashSet()) { it.id }
        return builtIn.map { subById[it.id] ?: it } +
            subscribed.filter { it.id !in builtInIds }
    }
}
