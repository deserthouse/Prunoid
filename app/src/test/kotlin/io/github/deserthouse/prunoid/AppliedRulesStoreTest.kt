package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 批Z1/Z3：账本 schema v2——按引擎分桶、v1 迁移、降级兼容形态、removeEngine 语义 */
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
        assertEquals(setOf("r1"), e.ruleIds)
        // v1 兼容顶栏与桶同源
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
        // v1 顶栏 = 双桶并集，最近引擎=PM
        assertEquals(setOf("p/A", "p/S").toSet(), e.components.toSet())
        assertEquals("PM", e.engine)
        assertEquals(setOf("r1", "r2"), e.ruleIds)
    }

    @Test
    fun `v1 json migrates to single bucket`() {
        val f = tmp.newFile("v1.json")
        // 0.18.1 实际落盘形态（schemaVersion/ifw/pm 字段缺失，ignoreUnknownKeys 反向：缺字段走默认值）
        f.writeText("""{"entries":{"pkg.v1":{"engine":"IFW","components":["p/X"],"types":{"p/X":"activity"},"at":123,"ruleIds":["r9"]}}}""")
        val s = AppliedRulesStore(f)
        val e = s.get("pkg.v1")!!
        assertEquals(listOf("p/X"), e.ifw?.components)
        assertNull(e.pm)
        assertEquals(setOf("r9"), e.ruleIds)
        // 迁移不扩权：组件与 v1 完全一致
        assertEquals(listOf("p/X"), e.components)
    }

    @Test
    fun `v2 file is downgrade-compatible shape`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        val raw = s.let { tmp.root.resolve("applied_rules.json").readText() }
        // 旧代码（0.18.1）可解码的关键字段必须在场
        assertTrue(raw.contains(""""engine":"""))
        assertTrue(raw.contains(""""components":"""))
        assertTrue(raw.contains(""""types":"""))
        assertTrue(raw.contains(""""at":"""))
        assertTrue(raw.contains(""""ruleIds":"""))
    }

    /** 模拟 0.18.1 旧代码的字段集与 Json 配置：decode v2 落盘文件必须不抛、v1 字段完整 */
    @Test
    fun `v2 file decodes with old 0_18_1 field set`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        val raw = tmp.root.resolve("applied_rules.json").readText()
        // 0.18.1 的 AppliedEntry：engine/components/types/at + ruleIds；Json{ignoreUnknownKeys=true}
        // v2 新增的 ifw/pm/schemaVersion 字段被忽略，v1 字段经 encodeDefaults=true 保证在场
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
    fun `removeEngine keeps the other engine bucket`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"), addedRuleIds = setOf("r1"))
        s.recordMerge("pkg.a", "PM", listOf("p/S"), mapOf("p/S" to "service"), addedRuleIds = setOf("r2"))
        s.recordMerge("pkg.b", "IFW", listOf("q/C"), mapOf("q/C" to "receiver"))
        s.removeEngine("IFW")
        val a = s.get("pkg.a")!!
        assertNull(a.ifw)
        assertEquals(listOf("p/S"), a.pm?.components)
        assertEquals(setOf("r1", "r2"), a.ruleIds) // 意图不随引擎桶清理丢失
        assertNull(s.get("pkg.b")) // 单桶全空 → 整条删除
    }

    @Test
    fun `replaceBuckets deletes entry when both empty`() {
        val s = newStore()
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), mapOf("p/A" to "activity"))
        s.replaceBuckets("pkg.a", ifw = null, pm = null, ruleIds = emptySet())
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
}
