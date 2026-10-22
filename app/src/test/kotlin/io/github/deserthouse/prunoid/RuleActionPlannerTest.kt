package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.engine.RuleActionPlanner
import io.github.deserthouse.prunoid.core.engine.SkipReason
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批M3（F02）：planRuleAction 四类样本——混合系统/受保护/provider-only/共享组件/空命中
 *  计数断言；IFW 与 PM 引擎资格差异（provider 归属）单测锁定。纯函数，无 Android 依赖。 */
class RuleActionPlannerTest {

    private fun app(
        pkg: String,
        label: String = pkg,
        isSystem: Boolean = false,
        ruleId: String = "r1",
        components: Map<String, String> = mapOf("p/A" to "activity"),
        extra: List<Pair<String, Map<String, String>>> = emptyList(),
    ): ScannedApp {
        val hits = listOf(SdkHit(ruleId, "R1", "ads", Safety.CAUTION, components.keys.toList(), components)) +
            extra.map { (rid, comps) -> SdkHit(rid, "X", "ads", Safety.CAUTION, comps.keys.toList(), comps) }
        return ScannedApp(pkg, label, isSystem, hits)
    }

    @Test
    fun `mixed sample categorizes eligible and skips`() {
        val apps = listOf(
            app("com.ok.app"),                                       // 可执行
            app("com.example.system", isSystem = true),              // SYSTEM
            app("com.google.android.gm"),                            // PROTECTED（保护前缀）
            app("com.prov.only", components = mapOf("p/Pr" to "provider")), // NO_WRITABLE（IFW）
        )
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps, rulesGeneration = 7)
        assertEquals(4, plan.hits)
        assertEquals(1, plan.eligibleCount)
        assertEquals(1, plan.totalComponents)
        assertEquals(3, plan.skipped.size)
        assertEquals(1, plan.skipCount(SkipReason.SYSTEM))
        assertEquals(1, plan.skipCount(SkipReason.PROTECTED))
        assertEquals(1, plan.skipCount(SkipReason.NO_WRITABLE_COMPONENTS))
        assertEquals(7, plan.rulesGeneration)
    }

    @Test
    fun `provider-only rule is ineligible under ifw but eligible under pm`() {
        val apps = listOf(app("com.prov.only", components = mapOf("p/Pr" to "provider", "p/N" to "native")))
        val ifw = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        assertEquals(0, ifw.eligibleCount)
        assertEquals(1, ifw.skipCount(SkipReason.NO_WRITABLE_COMPONENTS))
        val pm = RuleActionPlanner.planRuleAction("r1", Engine.PM, apps)
        assertEquals(1, pm.eligibleCount)
        assertEquals(2, pm.totalComponents)
    }

    @Test
    fun `shared components across rules are scoped per rule`() {
        // r1 与 r2 共享 p/A：每条规则的计划只消费自己的命中集——
        // r1 仅 com.a；r2 命中 com.a（共享锚点）+com.b，各自独立计数
        val apps = listOf(
            app("com.a", ruleId = "r1", components = mapOf("p/A" to "activity"), extra = listOf("r2" to mapOf("p/A" to "activity"))),
            app("com.b", ruleId = "r2", components = mapOf("p/B" to "service")),
        )
        val p1 = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val p2 = RuleActionPlanner.planRuleAction("r2", Engine.IFW, apps)
        assertEquals(1, p1.hits)
        assertEquals(1, p1.eligibleCount)
        assertEquals(2, p2.hits)
        assertEquals(2, p2.eligibleCount)
    }

    @Test
    fun `empty match yields zero plan`() {
        val plan = RuleActionPlanner.planRuleAction("rX", Engine.IFW, listOf(app("com.a")))
        assertEquals(0, plan.hits)
        assertEquals(0, plan.eligibleCount)
        assertEquals(0, plan.totalComponents)
        assertEquals(0, plan.skipped.size)
    }

    @Test
    fun `components are deduped and sorted deterministically`() {
        val apps = listOf(app("com.a", components = mapOf("p/B" to "activity", "p/A" to "activity", "p/C" to "service")))
        val plan = RuleActionPlanner.planRuleAction("r1", Engine.IFW, apps)
        val byType = plan.eligible.single().byType
        assertEquals(listOf("p/A", "p/B"), byType["activity"])
        assertEquals(listOf("p/C"), byType["service"])
        assertEquals(3, plan.totalComponents)
    }
}
