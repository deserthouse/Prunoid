package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// M3-R8：已应用规则持久化。批Z1（架构评审 A3）：schema v2——按引擎分桶记录「本工具
// 实际修改」，同包跨引擎不再互相覆盖（v1 单 engine 字段被 recordMerge 覆盖，恢复只走
// 最后引擎路径）；用户意图（ruleIds）与实际修改分离。
// 降级兼容：v2 落盘同时写 v1 顶栏字段（engine=最近引擎、components/types=双桶并集），
// 回装 0.18.1 时旧代码可解码不丢账本；v1 文件读取时迁移为单桶（preExisting 空=原状态未知）。
class AppliedRulesStore {

    @Serializable
    data class EngineChange(
        val components: List<String>,                 // 该引擎实际写入的组件全类名
        val types: Map<String, String> = emptyMap(),  // 组件 → 类型（IFW 分组用）
        val preExisting: List<String> = emptyList(),  // 批Z3：写入前已禁用（非本工具新增，恢复跳过）
        val at: Long = 0
    )

    @Serializable
    data class AppliedEntry(
        // v2：按引擎分桶（单引擎应用只填对应桶）
        val ifw: EngineChange? = null,
        val pm: EngineChange? = null,
        val ruleIds: Set<String> = emptySet(),        // X2：用户选择的规则 id 集（重应用范围）
        val schemaVersion: Int = 2,
        // v1 兼容顶栏：engine=最近引擎；components/types=双桶并集；旧代码可解码
        val engine: String = "IFW",
        val components: List<String> = emptyList(),
        val types: Map<String, String> = emptyMap(),
        val at: Long = 0
    ) {
        /** v1 迁移/v2 读取共用：该引擎桶（无则 null） */
        fun bucket(engine: String): EngineChange? = if (engine == "PM") pm else ifw
    }

    private val file: java.io.File

    // 测试注入口（JVM 单测用临时文件）；生产走 filesDir
    constructor(file: java.io.File) {
        this.file = file
    }

    constructor(context: Context) {
        this.file = java.io.File(context.filesDir, "applied_rules.json")
    }

    // encodeDefaults=true：v1 兼容顶栏字段（engine/components/types/at）即使取默认值也必须
    // 落盘——回装 0.18.1 的旧代码 decode 缺字段会抛异常→load() 兜底清空账本（降级静默丢失）
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Store(val entries: MutableMap<String, AppliedEntry> = mutableMapOf())

    private fun load(): Store =
        runCatching { json.decodeFromString<Store>(file.takeIf { it.exists() }?.readText() ?: return Store()) }
            .getOrDefault(Store())

    private fun save(store: Store) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Store.serializer(), store))
    }

    /** v1 文件迁移：顶栏字段 → 单桶（engine 字段决定桶位；旧条目 preExisting 空=原状态未知） */
    private fun migrated(e: AppliedEntry): AppliedEntry {
        if (e.ifw != null || e.pm != null) return e
        if (e.components.isEmpty() && e.types.isEmpty()) return e.copy(ifw = null, pm = null)
        val bucket = EngineChange(
            components = e.components,
            types = e.types,
            preExisting = emptyList(),
            at = e.at
        )
        return if (e.engine == "PM") e.copy(pm = bucket) else e.copy(ifw = bucket)
    }

    /** 双桶并集 → v1 兼容顶栏（engine=最近修改的引擎） */
    private fun withCompat(
        ifw: EngineChange?, pm: EngineChange?, ruleIds: Set<String>
    ): AppliedEntry {
        val latest = listOfNotNull(ifw, pm).maxByOrNull { it.at }
        val comps = LinkedHashSet<String>().apply {
            ifw?.let { addAll(it.components) }
            pm?.let { addAll(it.components) }
        }.toList()
        val types = LinkedHashMap<String, String>().apply {
            ifw?.let { putAll(it.types) }
            pm?.let { putAll(it.types) }
        }
        return AppliedEntry(
            ifw = ifw, pm = pm, ruleIds = ruleIds, schemaVersion = 2,
            engine = if (pm != null && (ifw == null || (pm.at >= ifw.at))) "PM" else "IFW",
            components = comps,
            types = types,
            at = latest?.at ?: 0
        )
    }

    /** 批X2（审查 R04）：合并语义——新增组件并入该引擎桶（不覆盖其他引擎桶） */
    fun recordMerge(
        pkg: String, engine: String,
        addedComponents: List<String>,
        addedTypes: Map<String, String>,
        addedRuleIds: Set<String> = emptySet(),
        preExisting: List<String> = emptyList()
    ) {
        if (addedComponents.isEmpty() && addedRuleIds.isEmpty()) return
        val store = load()
        val existing = store.entries[pkg]
        // 空条目不走迁移（否则空 v1 形态会迁出空 ifw 桶，干扰双桶判空）
        val old = if (existing == null) AppliedEntry() else migrated(existing)
        val isPm = engine == "PM"
        val cur = if (isPm) old.pm else old.ifw
        val merged = EngineChange(
            components = (cur?.components.orEmpty() + addedComponents).distinct(),
            types = cur?.types.orEmpty() + addedTypes,
            preExisting = (cur?.preExisting.orEmpty() + preExisting).distinct(),
            at = System.currentTimeMillis()
        )
        store.entries[pkg] = withCompat(
            ifw = if (isPm) old.ifw else merged,
            pm = if (isPm) merged else old.pm,
            ruleIds = old.ruleIds + addedRuleIds
        )
        save(store)
    }

    /** 批X2（审查 R04）：替换语义——该引擎桶替换；双桶全空 = 删除条目 */
    fun recordReplace(pkg: String, engine: String, components: List<String>, types: Map<String, String>, ruleIds: Set<String> = emptySet()) {
        val store = load()
        val old = migrated(store.entries[pkg] ?: AppliedEntry())
        val isPm = engine == "PM"
        val bucket = if (components.isEmpty()) null
        else EngineChange(components, types, preExisting = old.bucket(engine)?.preExisting.orEmpty(), at = System.currentTimeMillis())
        replaceBuckets(pkg, if (isPm) old.ifw else bucket, if (isPm) bucket else old.pm, ruleIds)
    }

    /** 批Z1：双桶直接替换（setSdkForApp off 跨桶清理用）；双桶全空 = 删除条目 */
    fun replaceBuckets(pkg: String, ifw: EngineChange?, pm: EngineChange?, ruleIds: Set<String>) {
        val store = load()
        if (ifw == null && pm == null) store.entries.remove(pkg)
        else store.entries[pkg] = withCompat(ifw, pm, ruleIds)
        save(store)
    }

    fun remove(pkg: String) {
        val store = load()
        if (store.entries.remove(pkg) != null) save(store)
    }

    /** 批Y3（架构评审 C4）：移除指定引擎的全部条目——应急清 IFW / 备份还原后调用；
     *  双桶包则保留另一引擎桶（PM 条目不受清 IFW 影响） */
    fun removeEngine(engine: String) {
        val store = load()
        val isPm = engine == "PM"
        val it = store.entries.entries.iterator()
        var changed = false
        while (it.hasNext()) {
            val entryRef = it.next()
            val en = migrated(entryRef.value)
            val target = if (isPm) en.pm else en.ifw
            if (target != null) {
                val other = if (isPm) en.ifw else en.pm
                if (other == null) it.remove()
                else entryRef.setValue(withCompat(if (isPm) en.ifw else null, if (isPm) null else en.pm, en.ruleIds))
                changed = true
            }
        }
        if (changed) save(store)
    }

    fun get(pkg: String): AppliedEntry? = load().entries[pkg]?.let { migrated(it) }

    fun all(): Map<String, AppliedEntry> = load().entries.mapValues { migrated(it.value) }
}
