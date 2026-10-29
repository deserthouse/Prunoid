package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.BulkPlanExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 批T1（C01）账本接线链：外部预禁用 → 批量 PM 记账（preExisting 贯通）→ 恢复目标排除。
 *  真实 AppliedRulesStore + 真实 pmPreExisting——复现审查探针的
 *  `preExisting=[] / restoreTargets 含外部组件` 反例并锁定为正确行为。
 *  VM 侧参数接线（readPmDisabled/owned 快照）与纯函数同构，Android VM 本体不在此实例化。 */
class BulkPmOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `external pre-disabled component survives restore after bulk pm apply`() {
        val store = AppliedRulesStore(tmp.newFile("applied_rules.json"))
        val targets = listOf("sdk.Service", "sdk.Activity")
        val beforeDisabled = setOf("sdk.Service")   // 外部/用户先在禁用
        val owned = emptySet<String>()              // 本工具 PM 桶尚无

        // —— 批量 PM 记账（VM 批量路径同构）：preExisting 由 pmPreExisting 计算 ——
        val preEx = BulkPlanExecutor.pmPreExisting(targets, beforeDisabled, owned)
        store.recordMerge(
            "pkg.a", "PM", targets,
            targets.associateWith { "service" },
            addedRuleIds = setOf("r1"),
            preExisting = preEx,
        )

        val entry = store.get("pkg.a")!!
        val pmBucket = entry.pm!!
        assertEquals(targets.toSet(), pmBucket.components.toSet())
        // 反例锁定：preExisting 必须含外部组件（原批量路径此值为空 → 恢复错误启用）
        assertEquals(setOf("sdk.Service"), pmBucket.preExisting.toSet())

        // —— 恢复目标计算（restoreApp 同构）：排除 preExisting ——
        val restoreTargets = pmBucket.components.filter { it !in pmBucket.preExisting.toSet() }
        assertEquals(listOf("sdk.Activity"), restoreTargets)
        assertTrue("sdk.Service" !in restoreTargets)
    }

    @Test
    fun `owned components are not polluted into preExisting on repeated apply`() {
        val store = AppliedRulesStore(tmp.newFile("applied_rules.json"))
        // 本工具已拥有 sdk.Service（首次 apply 记账后）；重复 apply 不得把自有禁用记为外部既有
        store.recordMerge(
            "pkg.a", "PM", listOf("sdk.Service"),
            mapOf("sdk.Service" to "service"),
            addedRuleIds = setOf("r1"),
        )
        val owned = store.get("pkg.a")!!.pm!!.components.toSet()
        val beforeDisabled = setOf("sdk.Service")  // 现场=本工具自己写入的

        val preEx = BulkPlanExecutor.pmPreExisting(listOf("sdk.Service"), beforeDisabled, owned)
        assertTrue(preEx.isEmpty())

        // 二次记账 preExisting 不增（RV-02：preExisting 只增不删，此处无需增）
        store.recordMerge(
            "pkg.a", "PM", listOf("sdk.Service"),
            mapOf("sdk.Service" to "service"),
            addedRuleIds = setOf("r1"),
            preExisting = preEx,
        )
        assertTrue(store.get("pkg.a")!!.pm!!.preExisting.isEmpty())
    }
}
