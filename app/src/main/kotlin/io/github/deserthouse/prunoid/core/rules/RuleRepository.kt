package io.github.deserthouse.prunoid.core.rules

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

// 规则仓库：内置冷启动快照 + 订阅源合并（订阅条目按 id 覆盖内置，其余追加）
class RuleRepository(context: Context, initialSources: List<SettingsRepository.SubSource>? = null) {

    private val subscription = RuleSubscription(context)
    private val settings = SettingsRepository(context)

    val snapshot: RuleSnapshot by lazy {
        val text = context.assets.open("rules/snapshot.json").bufferedReader().use { it.readText() }
        RuleRepository.json.decodeFromString<RuleSnapshot>(text)
    }

    /** 合并后的生效规则 = 内置 + 订阅覆盖（订阅同 id 覆盖，新 id 追加） */
    var effectiveRules: List<SdkRule> = emptyList()
        private set

    private var rulesById: Map<String, SdkRule> = emptyMap()

    /** 包名前缀倒排索引：前缀 -> 规则 id 列表 */
    var prefixIndex: Map<String, List<String>> = emptyMap()
        private set

    init {
        // 单例化前置：所有构造点（VM/自动重应用/守护服务/应急恢复）默认同步装载
        // 注册表全量源（含自定义源缓存），保证后台链路与 UI 使用同一规则集。
        // sources 缺省走 DataStore 同步读（filesDir IO，量小可控）。
        rebuild(initialSources ?: kotlinx.coroutines.runBlocking {
            settings.settings.first().sources
        })
    }

    /**
     * 重建合并视图与索引（多源）。
     * 合并口径：内置快照为底座；各源快照按注册表顺序依次并入——同 id 规则
     * 置信度高者胜（high>medium>low，平手保留先入），新 id 追加（并集语义）。
     */
    fun rebuild(sources: List<SettingsRepository.SubSource>) {
        val rules = MultiSourceMerger.mergeMultiSource(
            snapshot.sdks,
            sources.map { it.id }.mapNotNull { id -> subscription.cached(id)?.snapshot?.sdks }
        )
        // 批R5：同实体别名去重（保守合并，详见 RuleDedup.kt）
        effectiveRules = dedupRules(rules)
        rulesById = rules.associateBy { it.id }
        val m = HashMap<String, MutableList<String>>()
        for (r in rules) {
            for (p in r.packPrefixes) m.getOrPut(p) { mutableListOf() }.add(r.id)
        }
        prefixIndex = m
    }

    fun rule(id: String): SdkRule? = rulesById[id]

    /** 全量规则（SDK 库浏览页用） */
    fun allRules(): List<SdkRule> = effectiveRules

    fun match(packageName: String): List<SdkRule> {
        val hits = LinkedHashSet<String>()
        for ((prefix, ids) in prefixIndex) {
            if (packageName.startsWith(prefix)) hits.addAll(ids)
        }
        return hits.mapNotNull { rulesById[it] }
    }

    // ── 订阅管理（多源） ─────────────────────────────────────────
    /** 拉取/刷新指定源并重建合并视图（lastFetched 由调用方写回注册表） */
    suspend fun refreshSource(id: String, url: String): RuleSubscription.Result =
        subscription.fetchTo(id, url).also { if (it.ok) rebuild(subscribedSources) }

    fun clearSourceCache(id: String) = subscription.clear(id)

    /** 当前注册表（由 VM 从 Settings 注入，供 rebuild 与拉取使用） */
    @Volatile
    var subscribedSources: List<SettingsRepository.SubSource> = listOf(SettingsRepository.OFFICIAL_SOURCE)
        private set

    fun setSources(sources: List<SettingsRepository.SubSource>) {
        subscribedSources = sources
        rebuild(sources)
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
