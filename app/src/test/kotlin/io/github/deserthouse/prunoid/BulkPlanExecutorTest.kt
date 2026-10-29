package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.BulkPlanExecutor
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.engine.RuleActionPlanner
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批Q5（批Q/Q1+Q4，B01/B04 合同锁定）：伪引擎合同测试——断言 BulkPlanExecutor 按
 *  冻结计划**实际调用**的包/组件/引擎，而非只测 Planner；范围=计划 eligible（不重算、
 *  不扩面）、engine 分派（PM 计划零 IFW 调用）、执行期守卫复核、全失败逐项记账。
 *  报告 contract-probe.json 三反例（SCOPE/ENGINE/SWITCH）中前两者由此测试常绿锁定；
 *  SWITCH 反例（B02 描述与分派一致）为 Compose 语义层，AVD 走查以 content-desc 断言。 */
class BulkPlanExecutorTest {

    private fun app(
        pkg: String,
        ruleId: String = "r1",
        components: Map<String, String> = mapOf("p/A" to "activity"),
    ): ScannedApp {
        val hits = listOf(
            SdkHit(ruleId, "R1", "ads", Safety.CAUTION, components.keys.toList(), components)
        )
        return ScannedApp(pkg, pkg, false, hits)
    }

    private class FakeEngine(
        var ifwFailFor: Set<String> = emptySet(),
        var pmFailFor: Set<String> = emptySet(),
        // 批T2（C02）：部分失败构造——包名→失败组件集（其余组件成功）
        var pmPartialFor: Map<String, Set<String>> = emptyMap(),
        // 批T2：IFW 成功但零写入（Result.success(0) 不再冒报计划数）
        var ifwZeroWriteFor: Set<String> = emptySet(),
    ) {
        val ifwCalls = mutableListOf<Pair<String, Map<String, List<String>>>>()
        val pmCalls = mutableListOf<Pair<String, List<String>>>()

        val applyIfw: suspend (String, Map<String, List<String>>) -> Result<Int> =
            { pkg, byType ->
                ifwCalls += pkg to byType
                if (pkg in ifwFailFor) Result.failure(IllegalStateException("ifw write failed"))
                else Result.success(if (pkg in ifwZeroWriteFor) 0 else byType.values.sumOf { it.size })
            }
        // 批T2：PM 逐项回执（组件→成败）
        val applyPm: suspend (String, List<String>) -> List<Pair<String, Boolean>> =
            { pkg, comps ->
                pmCalls += pkg to comps
                val failed = pmPartialFor[pkg].orEmpty()
                comps.map { it to (pkg !in pmFailFor && it !in failed) }
            }
    }

    @Test
    fun `ifw plan calls only planned targets with planned components`() = runBlocking {
        // B01 SCOPE 反例锁定：计划=1 目标（筛选态），执行绝不能扩回全量命中
        val apps = listOf(app("com.ok.one"), app("com.ok.two"))
        val filteredPlan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, listOf(apps[0]), rulesGeneration = 3)
        val fake = FakeEngine()
        val exec = BulkPlanExecutor.execute(filteredPlan, fake.applyIfw, fake.applyPm)
        assertEquals(listOf("com.ok.one"), fake.ifwCalls.map { it.first })
        assertEquals(0, fake.pmCalls.size)
        assertEquals(Engine.IFW, exec.engine)
        assertEquals(1, exec.ok)
        assertEquals(listOf("com.ok.one"), exec.touched)
        assertEquals(1, exec.compWritten)
    }

    @Test
    fun `pm plan never touches ifw and passes flattened components`() = runBlocking {
        // B01 ENGINE 反例锁定：PM 计划（含 provider/native 全四类）分派 applyPm、零 applyIfw
        val apps = listOf(
            app("com.prov.only", components = mapOf("p/Pr" to "provider", "p/N" to "native")),
        )
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.PM, apps, rulesGeneration = 1)
        assertEquals(1, plan.eligibleCount)
        val fake = FakeEngine()
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(0, fake.ifwCalls.size)
        assertEquals(listOf("com.prov.only"), fake.pmCalls.map { it.first })
        // PM 组件=计划 byType 展平去重（含 provider/native）
        assertEquals(setOf("p/Pr", "p/N"), fake.pmCalls.single().second.toSet())
        assertEquals(2, exec.compWritten)
    }

    @Test
    fun `all-fail plan yields ok==0 with per-item failure detail`() = runBlocking {
        // B04：全失败必须可被 VM 识别为终态（原实现误报"无目标"且不记 Operation）
        val apps = listOf(app("com.a"), app("com.b"))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val fake = FakeEngine(ifwFailFor = setOf("com.a", "com.b"))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(0, exec.ok)
        assertEquals(2, exec.items.size)
        assertTrue(exec.items.all { !it.ok })
        assertTrue(exec.items.all { it.detail.isNotBlank() })
        assertEquals(0, exec.compWritten)
        assertTrue(exec.touched.isEmpty())
    }

    @Test
    fun `partial failure keeps successes in touched and ledger-eligible set`() = runBlocking {
        val apps = listOf(app("com.ok.app"), app("com.bad.app"))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val fake = FakeEngine(ifwFailFor = setOf("com.bad.app"))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(1, exec.ok)
        assertEquals(listOf("com.ok.app"), exec.touched)
        assertEquals(1, exec.items.count { it.ok })
        assertEquals(1, exec.items.count { !it.ok })
    }

    @Test
    fun `pm partial success counts real components and keeps package in touched`() = runBlocking {
        // 批T2（C02）：PM 部分成功——ok 只计全成功包，但部分成功包有真实写入：
        // 必须入 touched（现场回读）且 okComponents=成功集（账本只记成项）
        val apps = listOf(app("com.a", components = mapOf("p/X" to "service", "p/Y" to "receiver")))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.PM, apps)
        val fake = FakeEngine(pmPartialFor = mapOf("com.a" to setOf("p/X")))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(0, exec.ok)                      // 部分成功不计全成功
        assertEquals(listOf("com.a"), exec.touched)   // 但有真实写入→回读
        assertEquals(1, exec.compWritten)             // 账本只记成功组件
        val item = exec.items.single()
        assertEquals(false, item.ok)
        assertEquals(listOf("p/Y"), item.okComponents)
        assertTrue(item.detail.contains("partial 1/2"))
    }

    @Test
    fun `ifw zero-write success no longer inflates compWritten`() = runBlocking {
        // 批T2（C02）反例锁定：Result.success(0)（如 idempotent 无新增）不冒报计划数
        val apps = listOf(app("com.a", components = mapOf("p/X" to "activity")))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val fake = FakeEngine(ifwZeroWriteFor = setOf("com.a"))
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(1, exec.ok)
        assertEquals(0, exec.compWritten)
        assertEquals(0, exec.items.single().compWritten)
    }

    @Test
    fun `pmPreExisting keeps external disables and excludes owned`() {
        // 批T1（C01）：external = before ∩ targets − owned
        val out = BulkPlanExecutor.pmPreExisting(
            targets = listOf("p/X", "p/Y", "p/Z"),
            beforeDisabled = setOf("p/X", "p/Y", "p/W"),  // W 不在目标=与本工具无关
            owned = setOf("p/Y"),                          // Y 已是本工具写入
        )
        assertEquals(listOf("p/X"), out)
    }

    @Test
    fun `guard rejection skips engine call and records reason`() = runBlocking {
        // 执行期二次复核：资格在计划后失效的目标不触引擎、逐项记录原因
        val apps = listOf(app("com.ok.app"), app("com.revoked.app"))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val fake = FakeEngine()
        val exec = BulkPlanExecutor.execute(
            plan, fake.applyIfw, fake.applyPm,
            guard = { if (it == "com.revoked.app") "revoked" else null },
        )
        assertEquals(listOf("com.ok.app"), fake.ifwCalls.map { it.first })
        assertEquals(2, exec.items.size)
        assertEquals("revoked", exec.items.first { it.pkg == "com.revoked.app" }.detail)
        assertEquals(1, exec.ok)
    }

    @Test
    fun `default guard blocks protected prefixes without engine call`() = runBlocking {
        // 默认守卫接线 DisableEngine.isForbidden（与 Planner 同源前缀表）
        val apps = listOf(app("com.google.android.gm"), app("com.ok.app"))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        assertEquals(1, plan.eligibleCount) // planner 已跳过受保护包
        val fake = FakeEngine()
        val exec = BulkPlanExecutor.execute(plan, fake.applyIfw, fake.applyPm)
        assertEquals(listOf("com.ok.app"), fake.ifwCalls.map { it.first })
        assertEquals(1, exec.ok)
    }

    @Test
    fun `planId is stable per inputs and discriminates generation and scope`() {
        val apps = listOf(app("com.a"), app("com.b"))
        val p1 = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps, rulesGeneration = 3)
        val p1Again = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps, rulesGeneration = 3)
        val pGen = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps, rulesGeneration = 4)
        val pScope = RuleActionPlanner.planRuleAction("r1", Engine.IFW, listOf(apps[0]), rulesGeneration = 3)
        assertEquals(p1.planId, p1Again.planId)
        assertNotEquals(p1.planId, pGen.planId)
        assertNotEquals(p1.planId, pScope.planId)
        assertTrue(p1.planId.startsWith("r1@r3#"))
    }
}
