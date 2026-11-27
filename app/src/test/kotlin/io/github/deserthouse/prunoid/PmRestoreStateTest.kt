package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Action
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Cmd
import io.github.deserthouse.prunoid.core.engine.RestoreAccounting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 0.33.1：pm 组件的"写入前状态"——归属判定、恢复命令、撤销命令、账本持久化与合并。
 *
 *  背景：pm 恢复一律 `pm enable`，写入前是默认态的组件会被留下一条显式"已启用"记录（待修 2）。
 *  现在记账时写下写入前状态，恢复时默认态回默认态、显式启用回启用；本版本之前写的桶没有这张表，
 *  按"未知"处理，恢复仍用"启用"（旧行为不变）。 */
class PmRestoreStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val D = PmStateSnapshot.DEFAULT
    private val types = mapOf("p/A" to "activity", "p/B" to "service", "p/C" to "receiver", "p/Y" to "service")

    private fun newStore(name: String = "applied_rules.json") =
        AppliedRulesStore(java.io.File(tmp.root, name))  // 首次写入前主文件不存在（空文件自批Z4 起按损坏处理）

    private fun cmd(prev: Map<String, String>?, comp: String) = RestoreAccounting.restoreCmd(prev, comp)

    // ── 写入前的归属判定 ─────────────────────────────────────────────

    @Test
    fun `external disables become pre-existing and fresh comps keep their pre-write state`() {
        val states = mapOf("p/A" to D, "p/B" to "ENABLED", "p/C" to "DISABLED", "p/D" to "DISABLED_USER")
        val own = RestoreAccounting.pmOwnership(
            listOf("p/A", "p/B", "p/C", "p/D", "p/Gone"), states, owned = emptySet(),
        )!!
        // 写入前已禁用的（两种禁用态）= 外部既有，恢复跳过
        assertEquals(listOf("p/C", "p/D"), own.preExisting)
        // 其余组件的写入前状态原样入表；清单里已不存在的组件按默认态
        assertEquals(
            mapOf("p/A" to D, "p/B" to "ENABLED", "p/C" to "DISABLED", "p/D" to "DISABLED_USER", "p/Gone" to D),
            own.prevState,
        )
    }

    @Test
    fun `ownership is unknown when the pre-write read failed`() {
        assertNull(RestoreAccounting.pmOwnership(listOf("p/A"), null, owned = emptySet()))
    }

    @Test
    fun `components this tool already owns are neither external nor re-recorded`() {
        // p/B 此前由本工具停用（账本 PM 桶已有）：重复应用不得把它污染成"原本已禁用"
        val states = mapOf("p/A" to D, "p/B" to "DISABLED")
        val own = RestoreAccounting.pmOwnership(listOf("p/A", "p/B"), states, owned = setOf("p/B"))!!
        assertEquals(emptyList<String>(), own.preExisting)
        assertEquals(mapOf("p/A" to D), own.prevState)
    }

    // ── 恢复命令 ─────────────────────────────────────────────────────

    @Test
    fun `default-state comps go back to default and explicitly enabled ones back to enabled`() {
        val prev = mapOf("p/A" to D, "p/B" to "ENABLED")
        assertEquals(Cmd.DEFAULT, cmd(prev, "p/A"))
        assertEquals(Cmd.ENABLE, cmd(prev, "p/B"))
        // 表里没有的组件 = 状态未知 → 启用
        assertEquals(Cmd.ENABLE, cmd(prev, "p/Unknown"))
        assertEquals(
            listOf(Action("pkg", "p/A", Cmd.DEFAULT), Action("pkg", "p/B", Cmd.ENABLE)),
            RestoreAccounting.restoreActions("pkg", prev, listOf("p/A", "p/B")),
        )
    }

    @Test
    fun `ledger written before this version restores with enable as before`() {
        assertEquals(Cmd.ENABLE, cmd(null, "p/A"))
        assertTrue(RestoreAccounting.restoreActions("pkg", null, listOf("p/A", "p/B")).all { it.cmd == Cmd.ENABLE })
    }

    // ── 账本写失败时的撤销命令 ─────────────────────────────────────────

    @Test
    fun `rollback returns each newly disabled comp to its pre-write state and leaves external disables alone`() {
        val before = mapOf("p/A" to D, "p/B" to "ENABLED", "p/C" to "DISABLED", "p/D" to "DISABLED_USER")
        val acts = RestoreAccounting.rollbackActions("pkg", listOf("p/A", "p/B", "p/C", "p/D", "p/E"), before)!!
        // p/C、p/D 写入前就停用（外部状态）→ 不动；p/E 不在清单（按默认态）
        assertEquals(
            listOf(Action("pkg", "p/A", Cmd.DEFAULT), Action("pkg", "p/B", Cmd.ENABLE), Action("pkg", "p/E", Cmd.DEFAULT)),
            acts,
        )
    }

    @Test
    fun `rollback is refused when the pre-write state is unknown`() {
        // 分不清哪些是外部既有禁用——不能贸然启用
        assertNull(RestoreAccounting.rollbackActions("pkg", listOf("p/A"), null))
    }

    // ── 账本：持久化与合并 ─────────────────────────────────────────────

    @Test
    fun `pre-write state is persisted per component and drives the restore command`() {
        val s = newStore()
        s.recordMerge(
            "pkg.a", "PM", listOf("p/A", "p/B", "p/C"), types, addedRuleIds = setOf("r1"),
            preExisting = listOf("p/C"),
            prevState = mapOf("p/A" to D, "p/B" to "ENABLED", "p/C" to "DISABLED"),
        )
        // 另起一个实例从磁盘读：状态表确实落盘了
        val b = newStore().get("pkg.a")!!.pm!!
        assertEquals(listOf("p/C"), b.preExisting)
        assertEquals(mapOf("p/A" to D, "p/B" to "ENABLED", "p/C" to "DISABLED"), b.prevState)
        val restoreTargets = b.components.filter { it !in b.preExisting }
        assertEquals(listOf(Cmd.DEFAULT, Cmd.ENABLE), restoreTargets.map { cmd(b.prevState, it) })
        // IFW 桶没有这张表
        s.recordMerge("pkg.a", "IFW", listOf("p/A"), types, addedRuleIds = setOf("r1"))
        assertNull(newStore().get("pkg.a")!!.ifw!!.prevState)
    }

    @Test
    fun `ledger file written before this version decodes as unknown`() {
        // 旧版本写的 v2 桶：没有 prevState 键
        val f = tmp.newFile("ledger.json")
        f.writeText(
            """{"entries":{"pkg.old":{"ifw":null,"pm":{"components":["p/X"],"types":{"p/X":"service"},"preExisting":[],"ruleIds":["r1"],"at":5},""" +
                """"ruleIds":["r1"],"schemaVersion":3,"engine":"PM","components":["p/X"],"types":{"p/X":"service"},"at":5}}}""",
        )
        val s = AppliedRulesStore(f)
        val b = s.get("pkg.old")!!.pm!!
        assertNull(b.prevState)
        assertEquals(Cmd.ENABLE, cmd(b.prevState, "p/X"))
        // 在旧桶上再应用一个新组件（这次状态读到了）：整桶仍按未知处理，不拖累也不冒进
        s.recordMerge("pkg.old", "PM", listOf("p/Y"), types, addedRuleIds = setOf("r2"), prevState = mapOf("p/Y" to D))
        val b2 = s.get("pkg.old")!!.pm!!
        assertEquals(listOf("p/X", "p/Y"), b2.components)
        assertNull(b2.prevState)
    }

    @Test
    fun `re-apply keeps the first recorded state for owned comps and adds state for new comps`() {
        val s = newStore()
        s.recordMerge("pkg.a", "PM", listOf("p/A"), types, prevState = mapOf("p/A" to D))
        // 二次应用：p/A 已归本工具（现场=DISABLED，不在本次状态表里），p/B 是新增、写入前显式启用
        s.recordMerge("pkg.a", "PM", listOf("p/A", "p/B"), types, prevState = mapOf("p/B" to "ENABLED"))
        assertEquals(mapOf("p/A" to D, "p/B" to "ENABLED"), newStore().get("pkg.a")!!.pm!!.prevState)
    }

    @Test
    fun `a later apply that could not read the pre-write state does not erase what is known`() {
        val s = newStore()
        s.recordMerge("pkg.a", "PM", listOf("p/A", "p/B"), types, prevState = mapOf("p/A" to D, "p/B" to "ENABLED"))
        s.recordMerge("pkg.a", "PM", listOf("p/B"), types, prevState = null)
        assertEquals(mapOf("p/A" to D, "p/B" to "ENABLED"), newStore().get("pkg.a")!!.pm!!.prevState)
    }

    @Test
    fun `closing an sdk drops restored comps from the pre-write state table`() {
        val b = AppliedRulesStore.EngineChange(
            components = listOf("c1", "c2"), types = mapOf("c1" to "activity", "c2" to "activity"),
            prevState = mapOf("c1" to D, "c2" to "ENABLED"),
        )
        assertEquals(mapOf("c2" to "ENABLED"), RestoreAccounting.minusBucket(b, setOf("c1"), "sdk.a")!!.prevState)
        assertNull(RestoreAccounting.minusBucket(b.copy(prevState = null), setOf("c1"), "sdk.a")!!.prevState)
    }
}
