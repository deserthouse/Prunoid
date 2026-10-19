package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 批Z1/Z3 + 批BB RV-01/02/09：账本 schema——按引擎分桶（含桶内意图）、v1 迁移、降级兼容、所有权 */
class AppliedRulesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): AppliedRulesStore = AppliedRulesStore(tmp.newFile("applied_rules.json"))

    @Test
    fun `recordMerge fills engine bucket and v1 compat fields`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A", "p/B"), mapOf("p/A" to "activity", "p/B" to "service"), addedRuleIds = setOf("r1"))
        val e = s.get("pkg.a")!!
        assertEquals(2, e.ifw?.components?.size)
        assertNull(e.pm)
        // 批BB RV-02：意图入桶（兼容并集同源）
        assertEquals(setOf("r1"), e.ifw?.ruleIds)
        assertEquals(setOf("r1"), e.ruleIds)
        assertEquals("IFW", e.engine)
        assertEquals(e.ifw!!.components, e.components)
    }

    @Test
    fun `same package dual engines coexist without overwrite`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        s.recordMerge("pkg.a", "PM", listOf("p/S"), mapOf("p/S" to "service"), addedRuleIds = setOf("r2"))
        val e = s.get("pkg.a")!!
        assertEquals(listOf("p/A"), e.ifw?.components)
        assertEquals(listOf("p/S"), e.pm?.components)
        // 批BB RV-02：意图随桶——各自引擎只扩自己的规则
        assertEquals(setOf("r1"), e.ifw?.ruleIds)
        assertEquals(setOf("r2"), e.pm?.ruleIds)
        assertEquals(setOf("p/A", "p/S").toSet(), e.components.toSet())
        assertEquals("PM", e.engine)
        assertEquals(setOf("r1", "r2"), e.ruleIds)
    }

    @Test
    fun `v1 json migrates to single bucket`() {
        val f = tmp.newFile("v1.json")
        f.writeText("""{"entries":{"pkg.v1":{"engine":"IFW","components":["p/X"],"types":{"p/X":"activity"},"at":123,"ruleIds":["r9"]}}}""")
        val s = AppliedRulesStore(f)
        val e = s.get("pkg.v1")!!
        assertEquals(listOf("p/X"), e.ifw?.components)
        assertNull(e.pm)
        // v1 整组意图随迁移进单桶
        assertEquals(setOf("r9"), e.ifw?.ruleIds)
        assertEquals(listOf("p/X"), e.components)
    }

    @Test
    fun `v2 file is downgrade-compatible shape`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        val raw = tmp.root.resolve("applied_rules.json").readText()
        assertTrue(raw.contains(""""engine":"""))
        assertTrue(raw.contains(""""components":"""))
        assertTrue(raw.contains(""""types":"""))
        assertTrue(raw.contains(""""at":"""))
        assertTrue(raw.contains(""""ruleIds":"""))
    }

    /** 模拟 0.18.1 旧代码字段集与 Json 配置：decode v2 落盘文件必须不抛、v1 字段完整 */
    @Test
    fun `v2 file decodes with old 0_18_1 field set`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        val raw = tmp.root.resolve("applied_rules.json").readText()
        val oldJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        @kotlinx.serialization.Serializable
        data class OldEntry(
            val engine: String,
            val components: List<String>,
            val types: Map<String, String>,
            val at: Long = 0,
            val ruleIds: Set<String> = emptySet()
        )
        @kotlinx.serialization.Serializable
        data class OldStore(val entries: Map<String, OldEntry> = emptyMap())
        val decoded = oldJson.decodeFromString(OldStore.serializer(), raw)
        val e = decoded.entries["pkg.a"]!!
        assertEquals("IFW", e.engine)
        assertEquals(listOf("p/A"), e.components)
        assertEquals(setOf("r1"), e.ruleIds)
    }

    @Test
    fun `removeEngine keeps the other engine bucket and its intent`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        s.recordMerge("pkg.a", "PM", listOf("p/S"), mapOf("p/S" to "service"), addedRuleIds = setOf("r2"))
        s.recordMerge("pkg.b", "IFW", listOf("q/C"), mapOf("q/C" to "receiver"))
        s.removeEngine("IFW")
        val a = s.get("pkg.a")!!
        assertNull(a.ifw)
        assertEquals(listOf("p/S"), a.pm?.components)
        // 批BB RV-02：删桶即删该引擎意图——IFW 的 r1 不再保留（旧断言锁定存储结构、未测执行后果）
        assertEquals(setOf("r2"), a.pm?.ruleIds)
        assertEquals(setOf("r2"), a.ruleIds)
        assertNull(s.get("pkg.b"))
    }

    @Test
    fun `replaceBuckets deletes entry when both empty`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"))
        s.replaceBuckets("pkg.a", ifw = null, pm = null, fallbackRuleIds = emptySet())
        assertNull(s.get("pkg.a"))
    }

    @Test
    fun `recordMerge with preExisting keeps ownership record`() {
        val s = newStore()
        s.recordMerge("pkg.a", "PM", listOf("p/S1", "p/S2"), mapOf("p/S1" to "service"), preExisting = listOf("p/S1"))
        val e = s.get("pkg.a")!!
        assertEquals(listOf("p/S1"), e.pm?.preExisting)
        assertEquals(2, e.pm?.components?.size)
    }

    // ── 批BB RV-01/RV-09 ──

    @Test
    fun `preExisting merges additively and survives reapply`() {
        val s = newStore()
        // 第一次 apply：外部预禁 S1 → preExisting=[S1]
        s.recordMerge("pkg.a", "PM", listOf("p/S1", "p/S2"), mapOf("p/S1" to "service"), preExisting = listOf("p/S1"))
        // 第二次 apply（RV-01 修复后调用方排除自有组件 → 传空）
        s.recordMerge("pkg.a", "PM", listOf("p/S1", "p/S2"), mapOf("p/S1" to "service"), preExisting = emptyList())
        val e = s.get("pkg.a")!!
        assertEquals(listOf("p/S1"), e.pm?.preExisting)
        assertEquals(2, e.pm?.components?.size)
    }

    @Test
    fun `corrupted ledger keeps evidence and refuses writes until reset`() {
        val f = tmp.newFile("cor.json")
        f.writeText("""{"entries":{"pkg.a": BROKEN""")
        val s = AppliedRulesStore(f)
        // 首次读取触发损坏检测（构造不加载）
        assertTrue(s.all().isEmpty())
        assertTrue(s.corrupted)
        // 写操作被拒绝（不覆盖证据）
        val err = runCatching { s.recordMerge("pkg.b", "IFW", listOf("p/X"), mapOf("p/X" to "activity")) }
        assertTrue(err.isFailure)
        // 原始损坏文件（改名后的 .corrupt-*）未被覆盖
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("cor.json.corrupt-") })
        // 显式重置后可写
        s.resetCorrupted()
        s.recordMerge("pkg.b", "IFW", listOf("p/X"), mapOf("p/X" to "activity"))
        assertEquals(listOf("p/X"), s.get("pkg.b")?.components)
    }

    @Test
    fun `ledger write is atomic - tmp file not left behind`() {
        val dir = tmp.newFolder("atomic")
        val s = AppliedRulesStore(java.io.File(dir, "led.json"))
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"))
        // 写入完成后目录里只有账本本体（tmp 已 rename）
        val leftovers = dir.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
    }

    /** v2 共享 ruleIds（0.19/0.20 遗留形态：桶内无 ruleIds）迁移——桶内保持空=扩展保守暂停，组件不丢 */
    @Test
    fun `legacy v2 shared ruleIds migrate conservatively`() {
        val f = tmp.newFile("v2shared.json")
        f.writeText("""{"entries":{"pkg.a":{"ifw":{"components":["p/A"],"types":{"p/A":"activity"},"preExisting":[],"at":1},"pm":null,"ruleIds":["r1","r2"],"schemaVersion":2,"engine":"IFW","components":["p/A"],"types":{"p/A":"activity"},"at":1}}}""")
        val s = AppliedRulesStore(f)
        val e = s.get("pkg.a")!!
        assertEquals(listOf("p/A"), e.ifw?.components)
        // 桶内 ruleIds 空（无法反推归属）→ AutoReapply 桶内空=只保留不扩展
        assertTrue(e.ifw?.ruleIds!!.isEmpty())
        // 兼容并集仍含原集（组件与意图不丢）
        assertEquals(setOf("r1", "r2"), e.ruleIds)
    }
}
