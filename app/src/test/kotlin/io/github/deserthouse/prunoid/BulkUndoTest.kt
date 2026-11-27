package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.BulkPlanExecutor
import io.github.deserthouse.prunoid.core.engine.BulkPlanExecutor.TargetResult
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Action
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Cmd
import io.github.deserthouse.prunoid.core.engine.RestoreAccounting
import io.github.deserthouse.prunoid.core.engine.RuleActionPlanner
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.33.1：批量路径的两个缺口。
 *
 *  ① 账本写失败（待修 1）：原来只把提示加一句"未记账"，设备上的改动留着；重试时写前现场读分不清
 *     来源，把它们记成"原本已禁用"，之后恢复会跳过并声称非本工具设置。现在未记账的包自动撤销。
 *  ② 部分失败只报应用级 "partial 46/51"（待修 3）：现在结果卡条目列出失败的组件。 */
class BulkUndoTest {

    private val D = PmStateSnapshot.DEFAULT

    /** 伪引擎：记录收到的撤销调用；[device] 模拟各组件的真实启停状态 */
    private class FakeDevice {
        val device = HashMap<String, String>()
        var ifwOk = true
        var pmFailFor: Set<String> = emptySet()
        val ifwCalls = mutableListOf<Pair<String, String>>()
        val pmCalls = mutableListOf<Pair<String, List<Action>>>()

        val rollbackIfw: suspend (String, String) -> Boolean = { pkg, before ->
            ifwCalls += pkg to before
            ifwOk
        }
        val restorePm: suspend (String, List<Action>) -> List<Pair<String, Boolean>> = { pkg, acts ->
            pmCalls += pkg to acts
            acts.map { a ->
                val ok = a.comp !in pmFailFor
                if (ok) device[a.comp] = a.cmd.arg.let { arg ->
                    // 与 pm 命令一致的落点：default-state → DEFAULT，enable → ENABLED
                    if (arg == "enable") "ENABLED" else PmStateSnapshot.DEFAULT
                }
                a.comp to ok
            }
        }

        suspend fun undo(
            engine: Engine, unrecorded: List<TargetResult>,
            ifwBefore: Map<String, String> = emptyMap(),
            pmBefore: Map<String, Map<String, String>?> = emptyMap(),
        ) = BulkPlanExecutor.undoUnrecorded(engine, unrecorded, ifwBefore, pmBefore, rollbackIfw, restorePm)
    }

    private fun app(pkg: String, vararg comps: String) = ScannedApp(
        pkg, pkg, false,
        listOf(SdkHit("r1", "R1", "ads", Safety.CAUTION, comps.toList(), comps.associateWith { "service" })),
    )

    // ── ① 账本写失败后的撤销 ──────────────────────────────────────────

    @Test
    fun `after the undo a retry cannot misread this run as pre-existing`() = runBlocking {
        // 这就是登记的缺陷：不撤销的话，重试时写入前现场读到的全是"已禁用"，分不清是谁造成的
        val f = FakeDevice()
        val before = mapOf("com.a" to mapOf("p/X" to D, "p/Y" to "ENABLED", "p/Ext" to "DISABLED"))
        before["com.a"]!!.forEach { (c, s) -> f.device[c] = s }
        val targets = listOf("p/X", "p/Y", "p/Ext")
        // 本次批量把三个组件都停用；账本没写上
        targets.forEach { f.device[it] = "DISABLED" }
        val res = TargetResult("com.a", true, "", 3, targets)

        // 反例：不撤销直接重试 → 三个都被判成外部既有禁用，之后恢复会全部跳过
        val withoutUndo = RestoreAccounting.pmOwnership(targets, f.device, owned = emptySet())!!
        assertEquals(setOf("p/X", "p/Y", "p/Ext"), withoutUndo.preExisting.toSet())

        val out = f.undo(Engine.PM, listOf(res), pmBefore = before)
        assertEquals(1, out.undone)
        assertTrue(out.leftover.isEmpty())
        // 设备回到批量之前：本次造成的两条禁用各自回原状，外部那条原封不动
        assertEquals(mapOf("p/X" to D, "p/Y" to "ENABLED", "p/Ext" to "DISABLED"), f.device)
        // 重试时的归属判定：只有外部那条算既有禁用
        val afterUndo = RestoreAccounting.pmOwnership(targets, f.device, owned = emptySet())!!
        assertEquals(listOf("p/Ext"), afterUndo.preExisting)
    }

    @Test
    fun `ifw file is written back to its pre-apply content`() = runBlocking {
        val f = FakeDevice()
        val res = TargetResult("com.a", true, "", 2, listOf("p/X", "p/Y"))
        val out = f.undo(Engine.IFW, listOf(res), ifwBefore = mapOf("com.a" to "<rules/>"))
        assertEquals(1, out.undone)
        assertEquals(listOf("com.a" to "<rules/>"), f.ifwCalls)
        assertEquals(BulkPlanExecutor.ROLLED_BACK, out.detail["com.a"])
        assertTrue("pm must not be touched on the IFW engine", f.pmCalls.isEmpty())
    }

    @Test
    fun `ifw file that did not exist before is removed by the rollback`() = runBlocking {
        val f = FakeDevice()
        f.undo(Engine.IFW, listOf(TargetResult("com.a", true, "", 1, listOf("p/X"))))
        // 空串 = 恢复为"无此文件"（DisableEngine.rollbackIfw 的约定）
        assertEquals(listOf("com.a" to ""), f.ifwCalls)
    }

    @Test
    fun `pm package is left alone and reported when the pre-write state is unknown`() = runBlocking {
        val f = FakeDevice()
        val res = TargetResult("com.a", true, "", 2, listOf("p/X", "p/Y"))
        val out = f.undo(Engine.PM, listOf(res), pmBefore = mapOf("com.a" to null))
        assertTrue(f.pmCalls.isEmpty())
        assertEquals(listOf("com.a"), out.leftover)
        assertEquals(0, out.undone)
        assertEquals(BulkPlanExecutor.ROLLBACK_INCOMPLETE, out.detail["com.a"])
    }

    @Test
    fun `a failed restore receipt makes the package leftover`() = runBlocking {
        val f = FakeDevice().apply { pmFailFor = setOf("p/Y") }
        val res = TargetResult("com.a", true, "", 2, listOf("p/X", "p/Y"))
        val out = f.undo(Engine.PM, listOf(res), pmBefore = mapOf("com.a" to mapOf("p/X" to D, "p/Y" to D)))
        assertEquals(listOf("com.a"), out.leftover)
        assertEquals(0, out.undone)
    }

    @Test
    fun `packages are undone one by one and counted`() = runBlocking {
        val f = FakeDevice()
        val a = TargetResult("com.a", true, "", 1, listOf("p/X"))
        val b = TargetResult("com.b", true, "", 1, listOf("p/Y"))
        val out = f.undo(Engine.IFW, listOf(a, b), ifwBefore = mapOf("com.a" to "<a/>"))
        assertEquals(2, out.undone)
        assertTrue(out.leftover.isEmpty())
        assertEquals(listOf("com.a" to "<a/>", "com.b" to ""), f.ifwCalls)
    }

    // ── ② 结果条目列出失败组件 ─────────────────────────────────────────

    private class FakeEngine(var pmPartialFor: Map<String, Set<String>> = emptyMap(), var ifwFailFor: Set<String> = emptySet()) {
        val applyIfw: suspend (String, Map<String, List<String>>) -> Result<Int> = { pkg, byType ->
            if (pkg in ifwFailFor) Result.failure(IllegalStateException("ifw write failed"))
            else Result.success(byType.values.sumOf { it.size })
        }
        val applyPm: suspend (String, List<String>) -> List<Pair<String, Boolean>> = { pkg, comps ->
            comps.map { it to (it !in pmPartialFor[pkg].orEmpty()) }
        }
    }

    @Test
    fun `pm partial failure lists the failed components under the app`() = runBlocking {
        val plan = RuleActionPlanner.planRuleAction(
            "r1", Engine.PM, listOf(app("com.a", "p/Ok1", "p/Ok2", "p/Bad1", "p/Bad2")),
        )
        val fake = FakeEngine(pmPartialFor = mapOf("com.a" to setOf("p/Bad1", "p/Bad2")))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        val item = exec.items.single()
        assertEquals(listOf("p/Bad1", "p/Bad2"), item.failedComponents)
        assertEquals(listOf("p/Ok1", "p/Ok2"), item.okComponents)
        val card = BulkPlanExecutor.resultItem(item)
        assertEquals("com.a", card.target)
        assertEquals(false, card.ok)
        assertEquals(listOf("partial 2/4", "p/Bad1", "p/Bad2"), card.detail.split('\n'))
    }

    @Test
    fun `partial failure actually produced by a fake engine is reported the same way`() = runBlocking {
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.PM, listOf(app("com.a", "p/X", "p/Y")))
        val fake = FakeEngine(pmPartialFor = mapOf("com.a" to setOf("p/X")))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        val item = exec.items.single()
        assertEquals(listOf("p/Y"), item.okComponents)
        assertEquals(listOf("p/X"), item.failedComponents)
        assertTrue(BulkPlanExecutor.resultItem(item).detail.contains("partial 1/2"))
    }

    @Test
    fun `total failures list every planned component`() = runBlocking {
        val pm = RuleActionPlanner.planRuleAction("r1", Engine.PM, listOf(app("com.a", "p/X", "p/Y")))
        val pmFake = FakeEngine(pmPartialFor = mapOf("com.a" to setOf("p/X", "p/Y")))
        val pmExec = BulkPlanExecutor.execute(pm, pmFake.applyIfw, pmFake.applyPm)
        assertEquals(listOf("p/X", "p/Y"), pmExec.items.single().failedComponents)

        val ifw = RuleActionPlanner.planRuleAction("r1", Engine.IFW, listOf(app("com.b", "p/X", "p/Y")))
        val ifwExec = BulkPlanExecutor.execute(ifw, FakeEngine(ifwFailFor = setOf("com.b")).applyIfw, FakeEngine().applyPm)
        assertEquals(listOf("p/X", "p/Y"), ifwExec.items.single().failedComponents)
    }

    @Test
    fun `guard rejection and success do not invent failed components`() = runBlocking {
        val plan = RuleActionPlanner.planRuleAction(
            "r1", Engine.IFW, listOf(app("com.ok", "p/A"), app("com.revoked", "p/B")),
        )
        val exec = BulkPlanExecutor.execute(plan, FakeEngine().applyIfw, FakeEngine().applyPm, guard = { if (it == "com.revoked") "revoked" else null })
        val ok = exec.items.first { it.pkg == "com.ok" }
        val revoked = exec.items.first { it.pkg == "com.revoked" }
        assertTrue(ok.failedComponents.isEmpty())
        assertEquals("", BulkPlanExecutor.resultItem(ok).detail)
        assertTrue(revoked.failedComponents.isEmpty())
        assertEquals("revoked", BulkPlanExecutor.resultItem(revoked).detail)
    }

    @Test
    fun `long failure lists are capped with the remainder counted`() {
        val failed = (1..15).map { "p/C$it" }
        val item = TargetResult("com.a", false, "pm disable failed", 0, emptyList(), failedComponents = failed)
        val lines = BulkPlanExecutor.resultItem(item, max = 12).detail.split('\n')
        assertEquals("pm disable failed", lines[0])
        assertEquals((1..12).map { "p/C$it" }, lines.drop(1).dropLast(1))
        assertEquals("… +3", lines.last())
        // 默认上限 60：常见规则集（如穿山甲 51 个组件）全部列出，不截断
        assertEquals(listOf("pm disable failed") + failed, BulkPlanExecutor.resultItem(item).detail.split('\n'))
        // 上限不小于 1
        assertEquals(listOf("pm disable failed", "p/C1", "… +14"), BulkPlanExecutor.resultItem(item, max = 1).detail.split('\n'))
    }

    @Test
    fun `undo constants name the two outcomes for the result card`() {
        assertEquals("ledger write failed, change rolled back", BulkPlanExecutor.ROLLED_BACK)
        assertEquals("ledger write failed, rollback incomplete", BulkPlanExecutor.ROLLBACK_INCOMPLETE)
        assertEquals(Cmd.DEFAULT, RestoreAccounting.restoreCmd(mapOf("p/X" to D), "p/X"))
    }
}
