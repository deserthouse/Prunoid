package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// M3-R8：已应用规则持久化——记录每个包被应用的引擎与组件目标，
// 供 PACKAGE_ADDED/REPLACED 后自动增量重应用（更新可能引入新组件）
class AppliedRulesStore(context: Context) {

    @Serializable
    data class AppliedEntry(
        val engine: String,               // IFW | PM
        val components: List<String>,     // 组件全类名
        val types: Map<String, String>,   // 组件全类名 -> 类型（IFW 分组用）
        val at: Long = 0,                 // 应用时间（epoch ms；旧条目为 0 则不展示时间）
        // 批X2（审查 R05）：产生本条记录的规则 id 集——自动重应用只在此范围内扩展，不再全量 SAFE/CAUTION
        val ruleIds: Set<String> = emptySet()
    )

    private val file = java.io.File(context.filesDir, "applied_rules.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Store(val entries: MutableMap<String, AppliedEntry> = mutableMapOf())

    private fun load(): Store =
        runCatching { json.decodeFromString<Store>(file.takeIf { it.exists() }?.readText() ?: return Store()) }
            .getOrDefault(Store())

    private fun save(store: Store) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Store.serializer(), store))
    }

    /** 批X2（审查 R04）：合并语义——新增组件并入既有记录（不覆盖同包其他 SDK 的目标） */
    fun recordMerge(
        pkg: String, engine: String,
        addedComponents: List<String>,
        addedTypes: Map<String, String>,
        addedRuleIds: Set<String> = emptySet()
    ) {
        if (addedComponents.isEmpty() && addedRuleIds.isEmpty()) return
        val store = load()
        val old = store.entries[pkg]
        store.entries[pkg] = AppliedEntry(
            engine = engine,
            components = (old?.components.orEmpty() + addedComponents).distinct(),
            types = old?.types.orEmpty() + addedTypes,
            at = System.currentTimeMillis(),
            ruleIds = old?.ruleIds.orEmpty() + addedRuleIds
        )
        save(store)
    }

    /** 批X2（审查 R04）：替换语义——components 为空 = 该包已无本工具目标，删除记录（旧实现空集直接 return 是 R04-b 根因） */
    fun recordReplace(pkg: String, engine: String, components: List<String>, types: Map<String, String>, ruleIds: Set<String> = emptySet()) {
        val store = load()
        if (components.isEmpty()) {
            store.entries.remove(pkg)
        } else {
            store.entries[pkg] = AppliedEntry(engine, components, types, System.currentTimeMillis(), ruleIds)
        }
        save(store)
    }

    fun remove(pkg: String) {
        val store = load()
        if (store.entries.remove(pkg) != null) save(store)
    }

    /** 批Y3（架构评审 C4）：移除指定引擎的全部条目——应急清 IFW 后调用；
     *  其他引擎（PM）条目保留：pm 禁用状态不受清 IFW 影响，其管理意图仍然有效 */
    fun removeEngine(engine: String) {
        val store = load()
        val before = store.entries.size
        store.entries.values.removeAll { it.engine == engine }
        if (store.entries.size != before) save(store)
    }

    fun get(pkg: String): AppliedEntry? = load().entries[pkg]

    fun all(): Map<String, AppliedEntry> = load().entries
}
