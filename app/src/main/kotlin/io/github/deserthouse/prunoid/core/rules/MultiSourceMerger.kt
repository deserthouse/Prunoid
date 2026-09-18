package io.github.deserthouse.prunoid.core.rules

/**
 * 多源合并纯函数：内置快照为底座，各源按序并入——同 id 置信度高者胜
 * （high>medium>low，平手保留先入），新 id 追加（并集语义）。
 * 可单测；RuleRepository.rebuild 的核心逻辑。
 */
object MultiSourceMerger {

    private fun rank(c: String?): Int = when (c) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    fun mergeMultiSource(
        builtIn: List<SdkRule>,
        subscribedLists: List<List<SdkRule>>
    ): List<SdkRule> {
        val merged = builtIn.associateByTo(LinkedHashMap()) { it.id }
        for (list in subscribedLists) {
            for (r in list) {
                val prev = merged[r.id]
                when {
                    prev == null -> merged[r.id] = r
                    rank(r.confidence) > rank(prev.confidence) -> merged[r.id] = r
                }
            }
        }
        return merged.values.toList()
    }
}
