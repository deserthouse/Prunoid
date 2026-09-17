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
        val at: Long = 0                  // 应用时间（epoch ms；旧条目为 0 则不展示时间）
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

    fun record(pkg: String, engine: String, components: List<String>, types: Map<String, String>) {
        if (components.isEmpty()) return
        val store = load()
        store.entries[pkg] = AppliedEntry(engine, components, types, System.currentTimeMillis())
        save(store)
    }

    fun remove(pkg: String) {
        val store = load()
        if (store.entries.remove(pkg) != null) save(store)
    }

    fun get(pkg: String): AppliedEntry? = load().entries[pkg]

    fun all(): Map<String, AppliedEntry> = load().entries
}
