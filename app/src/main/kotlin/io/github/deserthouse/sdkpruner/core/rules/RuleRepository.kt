package io.github.deserthouse.sdkpruner.core.rules

import android.content.Context
import kotlinx.serialization.json.Json

// 规则仓库：内置冷启动快照 + 订阅源合并（订阅条目按 id 覆盖内置，其余追加）
class RuleRepository(context: Context) {

    private val subscription = RuleSubscription(context)

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
        rebuild()
    }

    /** 重建合并视图与索引（订阅变更后调用） */
    fun rebuild() {
        val rules = RuleMerger.merge(snapshot.sdks, subscription.loadSubscribed())
        effectiveRules = rules
        rulesById = rules.associateBy { it.id }
        val m = HashMap<String, MutableList<String>>()
        for (r in rules) {
            for (p in r.packPrefixes) m.getOrPut(p) { mutableListOf() }.add(r.id)
        }
        prefixIndex = m
    }

    fun rule(id: String): SdkRule? = rulesById[id]

    fun match(packageName: String): List<SdkRule> {
        val hits = LinkedHashSet<String>()
        for ((prefix, ids) in prefixIndex) {
            if (packageName.startsWith(prefix)) hits.addAll(ids)
        }
        return hits.mapNotNull { rulesById[it] }
    }

    // ── 订阅管理（委托） ──────────────────────────────────────────
    suspend fun subscribe(url: String): RuleSubscription.Result =
        subscription.fetch(url).also { if (it.ok) rebuild() }

    fun subscriptionInfo(): Pair<String?, String?> =
        subscription.cached()?.let { it.url to it.fetchedAt } ?: (null to null)

    /** 订阅元数据（设置页用）：url / 拉取时间 / 规则条数；未订阅返回 null */
    data class SubMeta(val url: String, val fetchedAt: String, val sdkCount: Int)

    fun subscriptionMeta(): SubMeta? =
        subscription.cached()?.let { SubMeta(it.url, it.fetchedAt, it.snapshot.sdks.size) }

    fun unsubscribe() {
        subscription.clear()
        rebuild()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
