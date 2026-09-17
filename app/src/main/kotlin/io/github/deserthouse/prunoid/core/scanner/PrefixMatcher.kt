package io.github.deserthouse.prunoid.core.scanner

import io.github.deserthouse.prunoid.core.rules.SdkRule

// 组件前缀匹配器：把只有 packPrefixes 的规则（blocker-general-rules 468 条 /
// oF2pks 619 条）变成可禁用目标——组件全类名以前缀开头即命中
// 索引按前缀前两段分桶，避免每组件 × 全量前缀的线性扫描
class PrefixMatcher(rules: List<SdkRule>) {

    private data class Entry(val needle: String, val ruleId: String, val contains: Boolean)

    // 桶键 -> 候选；键为前缀前两段；不足两段或"包含"语义（Exodus 式 .foo. 前导点）进 "" 通配桶
    private val buckets: Map<String, List<Entry>> = build(rules)

    private fun build(rules: List<SdkRule>): Map<String, List<Entry>> {
        val m = HashMap<String, MutableList<Entry>>()
        for (r in rules) {
            for (p in r.packPrefixes) {
                val contains = p.startsWith(".")
                val norm = p.trim('.').let { if (contains) "$it." else it }
                if (norm.isEmpty()) continue
                val segs = norm.split('.').filter { it.isNotBlank() }
                val key = if (!contains && segs.size >= 2) "${segs[0]}.${segs[1]}" else ""
                m.getOrPut(key) { mutableListOf() }.add(Entry(norm, r.id, contains))
            }
        }
        return m
    }

    /** 返回命中的规则 id 集合 */
    fun match(className: String): Set<String> {
        val segs = className.split('.').filter { it.isNotBlank() }
        if (segs.isEmpty()) return emptySet()
        val key = if (segs.size >= 2) "${segs[0]}.${segs[1]}" else segs[0]
        val cands = buckets[key].orEmpty() + buckets[""].orEmpty()
        if (cands.isEmpty()) return emptySet()
        return cands.filter { e ->
            if (e.contains) className.contains(e.needle) else className.startsWith(e.needle)
        }.mapTo(LinkedHashSet()) { it.ruleId }
    }
}
