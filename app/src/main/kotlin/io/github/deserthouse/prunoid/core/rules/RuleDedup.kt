package io.github.deserthouse.prunoid.core.rules

// 批R5：同实体多规则去重（judge 实锤 Aurora Push/极光推送 安全评级相反、个推同名两条、
// Signal360 ×4 等）。数据侧根治在规则仓管线；此处为运行时保守合并，原则：
// safeToBlock 取 AND（只升不造）、confidence 取组内最低、组件/前缀/来源取并集。
// 别名表随快照 aliases 字段下发；同名组自动归并。不做模糊启发（宁缺毋滥）。

private val CONF_RANK = mapOf("high" to 2, "medium" to 1, "low" to 0)

data class DedupResult(
    val rules: List<SdkRule>,
    val oldToCanonicalId: Map<String, String>,
    val nameAliases: Map<String, Set<String>>
)

/** 结构化版：附带 旧id to 主id 映射 与 规范名 to 别名集合（搜索索引/索引归一用） */
fun dedupRulesDetailed(rules: List<SdkRule>, aliases: Map<String, String> = emptyMap()): DedupResult {
    val canonical = rules.map { aliases[it.name] ?: it.name }
    val groups = LinkedHashMap<String, MutableList<SdkRule>>()
    rules.forEachIndexed { i, r -> groups.getOrPut(canonical[i]) { mutableListOf() }.add(r) }
    val oldToId = HashMap<String, String>()
    val aliasesByName = HashMap<String, MutableSet<String>>()
    val out = groups.map { (name, group) ->
        val primary = group.sortedWith(
            compareByDescending<SdkRule> { CONF_RANK[it.confidence] ?: 0 }
                .thenByDescending { it.components.size }
        ).first()
        group.forEach { oldToId[it.id] = primary.id }
        val others = group.map { it.name }.filter { it != name }.toMutableSet()
        if (others.isNotEmpty()) aliasesByName[name] = others
        if (group.size == 1) group[0] else mergeGroup(name, group)
    }
    return DedupResult(out, oldToId, aliasesByName)
}

/** aliases：别名 → 规范名（数据侧经快照 aliases 字段下发；app 内不再硬编码） */
fun dedupRules(rules: List<SdkRule>, aliases: Map<String, String> = emptyMap()): List<SdkRule> {
    val canonical = rules.map { aliases[it.name] ?: it.name }
    val groups = LinkedHashMap<String, MutableList<SdkRule>>()
    rules.forEachIndexed { i, r -> groups.getOrPut(canonical[i]) { mutableListOf() }.add(r) }
    // 保持首见顺序输出（UI 列表顺序确定性）
    return rules.map { canonical[rules.indexOf(it)] }.distinct().mapNotNull { name ->
        groups[name]?.let { if (it.size == 1) it[0] else mergeGroup(name, it) }
    }.distinct()
}

private fun mergeGroup(canonicalName: String, group: List<SdkRule>): SdkRule {
    // 主条目 = 置信度最高、组件锚点最多者（id 稳定，保证 applied 记录与图标映射不漂移）
    val primary = group.sortedWith(
        compareByDescending<SdkRule> { CONF_RANK[it.confidence] ?: 0 }
            .thenByDescending { it.components.size }
    ).first()
    val lo = group.minBy { CONF_RANK[it.confidence] ?: 0 }
    return primary.copy(
        name = canonicalName,
        safeToBlock = group.all { it.safeToBlock },
        packPrefixes = group.flatMap { it.packPrefixes }.distinct(),
        components = group.flatMap { it.components }.distinct(),
        sources = group.flatMap { it.sources }.distinct(),
        contributors = group.flatMap { it.contributors }.distinct(),
        description = group.firstOrNull { it.description.isNotBlank() }?.description ?: primary.description,
        company = group.firstOrNull { it.company.isNotBlank() }?.company ?: primary.company,
        confidence = lo.confidence
    )
}
