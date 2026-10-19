package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// M3-R8：已应用规则持久化。批Z1：schema v2 按引擎分桶；批BB RV-02：意图（ruleIds）随桶——
// 清某引擎桶即终止该引擎的规则选择，跨引擎不再串用共享集。
// 降级兼容：落盘同时写 v1 顶栏字段（encodeDefaults=true 强制全字段），旧代码可解码不丢。
// 批BB RV-09：原子写（tmp+rename）；损坏文件改名保证据并置 corrupted（写操作拒绝覆盖恢复依据）。
class AppliedRulesStore {

    @Serializable
    data class EngineChange(
        val components: List<String>,                 // 该引擎实际写入的组件全类名
        val types: Map<String, String> = emptyMap(),  // 组件 → 类型（IFW 分组用）
        val preExisting: List<String> = emptyList(),  // 批Z3：写入前已禁用（非本工具新增，恢复跳过）
        // 批BB RV-02：该引擎的用户规则选择——重应用增量只扩展桶内 id，跨引擎不串用
        val ruleIds: Set<String> = emptySet(),
        val at: Long = 0
    )

    @Serializable
    data class AppliedEntry(
        // v2：按引擎分桶
        val ifw: EngineChange? = null,
        val pm: EngineChange? = null,
        // 兼容并集（降级字段）：写入时=双桶 ruleIds 汇总；读取 v1 时=原始整组
        val ruleIds: Set<String> = emptySet(),
        val schemaVersion: Int = 3,
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

    // encodeDefaults=true：v1 兼容顶栏字段即使取默认值也必须落盘（旧代码 decode 缺字段会抛）
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 批BB RV-09：账本损坏标志——置位后写操作拒绝（防覆盖恢复依据），由用户显式重置解除。
     *  状态外置：.corrupt-* 证据文件存在即损坏（corrupted 属于文件而非实例——
     *  多实例下先到的实例会把主文件改名，后到实例看"文件不存在"不能误判健康） */
    @Volatile
    private var corruptedFlag = false

    val corrupted: Boolean
        get() = corruptedFlag || corruptEvidenceExists()

    private fun corruptEvidenceExists(): Boolean =
        file.parentFile?.listFiles()?.any { it.name.startsWith(file.name + ".corrupt-") } ?: false

    @Serializable
    private data class Store(val entries: MutableMap<String, AppliedEntry> = mutableMapOf())

    private fun load(): Store {
        if (!file.exists()) return Store()
        val text = file.readText()
        // 空文件 = 合法空账本（首次写前中断等），不得判损坏锁死账本
        if (text.isBlank()) return Store()
        return try {
            json.decodeFromString<Store>(text)
        } catch (e: Exception) {
            // 批BB RV-09：损坏 ≠ 空——原文件改名保留恢复依据，置位拒绝写入
            val ts = System.currentTimeMillis()
            runCatching {
                val f = java.io.File(file.parentFile, file.name + ".corrupt-$ts")
                file.renameTo(f) || runCatching { file.copyTo(f, overwrite = true); file.delete() }.isSuccess
            }
            corruptedFlag = true
            Store()
        }
    }

    private fun save(store: Store) {
        // 批BB RV-09：损坏状态下拒绝普通写入（覆盖即销毁恢复依据）；重置由用户显式触发
        check(!corrupted) { "ledger corrupted — reset required before writing" }
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Store.serializer(), store))
        // 原子替换：同目录 rename；Windows 目标残留时先清
        if (file.exists() && !file.delete()) {
            tmp.delete()
            error("ledger atomic replace failed: cannot remove old file")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("ledger atomic rename failed")
        }
    }

    /** 批BB RV-09：损坏后显式重置（用户已确认丢弃恢复依据——.corrupt 证据文件一并删除） */
    fun resetCorrupted() {
        corruptedFlag = false
        file.parentFile?.listFiles()?.forEach { f ->
            if (f.name.startsWith(file.name + ".corrupt-")) f.delete()
        }
    }

    /** v1 文件迁移：顶栏字段 → 单桶（engine 字段决定桶位；旧条目 preExisting 空=原状态未知）。
     *  v2 共享 ruleIds（0.19/0.20 写的桶内无 ruleIds）无法反推归属——桶内保持空（增量扩展保守暂停），
     *  组件与兼容并集不动（不丢数据）。 */
    private fun migrated(e: AppliedEntry): AppliedEntry {
        if (e.ifw != null || e.pm != null) return e
        if (e.components.isEmpty() && e.types.isEmpty()) return e.copy(ifw = null, pm = null)
        val bucket = EngineChange(
            components = e.components,
            types = e.types,
            preExisting = emptyList(),
            ruleIds = e.ruleIds,
            at = e.at
        )
        return if (e.engine == "PM") e.copy(pm = bucket) else e.copy(ifw = bucket)
    }

    /** 双桶 → v1 兼容顶栏（engine=最近修改的引擎；ruleIds=双桶并集） */
    private fun withCompat(ifw: EngineChange?, pm: EngineChange?, fallbackRuleIds: Set<String>): AppliedEntry {
        val latest = listOfNotNull(ifw, pm).maxByOrNull { it.at }
        val comps = LinkedHashSet<String>().apply {
            ifw?.let { addAll(it.components) }
            pm?.let { addAll(it.components) }
        }.toList()
        val types = LinkedHashMap<String, String>().apply {
            ifw?.let { putAll(it.types) }
            pm?.let { putAll(it.types) }
        }
        val ids = LinkedHashSet<String>().apply {
            ifw?.let { addAll(it.ruleIds) }
            pm?.let { addAll(it.ruleIds) }
            addAll(fallbackRuleIds)
        }
        return AppliedEntry(
            ifw = ifw, pm = pm, ruleIds = ids, schemaVersion = 3,
            engine = if (pm != null && (ifw == null || (pm.at >= ifw.at))) "PM" else "IFW",
            components = comps,
            types = types,
            at = latest?.at ?: 0
        )
    }

    /** 批X2（审查 R04）：合并语义——新增组件并入该引擎桶（不覆盖其他引擎桶）。
     *  批BB RV-02：addedRuleIds 入当前引擎桶；preExisting 只增不删。 */
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
            ruleIds = cur?.ruleIds.orEmpty() + addedRuleIds,
            at = System.currentTimeMillis()
        )
        store.entries[pkg] = withCompat(
            ifw = if (isPm) old.ifw else merged,
            pm = if (isPm) merged else old.pm,
            fallbackRuleIds = emptySet()
        )
        save(store)
    }

    /** 批X2（审查 R04）：替换语义——该引擎桶替换；双桶全空 = 删除条目 */
    fun recordReplace(pkg: String, engine: String, components: List<String>, types: Map<String, String>, ruleIds: Set<String> = emptySet()) {
        val store = load()
        val old = migrated(store.entries[pkg] ?: AppliedEntry())
        val isPm = engine == "PM"
        val bucket = if (components.isEmpty()) null
        else EngineChange(
            components, types,
            preExisting = old.bucket(engine)?.preExisting.orEmpty(),
            ruleIds = (old.bucket(engine)?.ruleIds.orEmpty() + ruleIds),
            at = System.currentTimeMillis()
        )
        replaceBuckets(pkg, if (isPm) old.ifw else bucket, if (isPm) bucket else old.pm, emptySet())
    }

    /** 批Z1：双桶直接替换（setSdkForApp off 跨桶清理用）；双桶全空 = 删除条目。
     *  批BB RV-02：桶内 ruleIds 由调用方按桶维护（fallbackRuleIds 仅在双桶皆空判删后无意义） */
    fun replaceBuckets(pkg: String, ifw: EngineChange?, pm: EngineChange?, fallbackRuleIds: Set<String>) {
        val store = load()
        if (ifw == null && pm == null) store.entries.remove(pkg)
        else store.entries[pkg] = withCompat(ifw, pm, fallbackRuleIds)
        save(store)
    }

    fun remove(pkg: String) {
        val store = load()
        if (store.entries.remove(pkg) != null) save(store)
    }

    /** 批Y3（架构评审 C4）：移除指定引擎的全部条目——应急清 IFW / 备份还原后调用。
     *  批BB RV-02：删桶即删该引擎的规则选择（withCompat 汇总自然收窄） */
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
                else entryRef.setValue(withCompat(if (isPm) en.ifw else null, if (isPm) null else en.pm, emptySet()))
                changed = true
            }
        }
        if (changed) save(store)
    }

    /** 批Z0（RV-05）：双引擎全清——备份归档覆盖 IFW 目录+pm 状态，全局时点还原后无存活修改 */
    fun clearAll() {
        val store = load()
        if (store.entries.isNotEmpty()) {
            store.entries.clear()
            save(store)
        }
    }

    fun get(pkg: String): AppliedEntry? = load().entries[pkg]?.let { migrated(it) }

    fun all(): Map<String, AppliedEntry> = load().entries.mapValues { migrated(it.value) }
}
