package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.RestoreAccounting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.32.1（第六轮审查 R6-02）：恢复记账算术的表驱动测试——此前这段逻辑只在 ViewModel 里，
 *  行为正确性只有设备实证（锁 IFW 文件、锁账本）。用例取自设备上实测过的场景。 */
class RestoreAccountingTest {

    private fun ok(vararg cs: String): List<Pair<String, Boolean>> = cs.map { it to true }
    private fun bad(vararg cs: String): List<Pair<String, Boolean>> = cs.map { it to false }

    private data class Off(
        val name: String,
        val removed: List<String>,
        val ifwOwned: Set<String>,
        val pmTargets: List<String>,
        val receipts: List<Pair<String, Boolean>>,
        val ifwOk: Boolean,
        val owned: Int,
        val restored: Int,
        val failed: Int,
        val ifwMinus: Set<String>,
        val pmMinus: Set<String>,
        val allFailed: Boolean = false,
        val partial: Boolean = false,
    )

    private val offCases = listOf(
        // 全成功（IFW 单桶）
        Off(
            "all restored, ifw only", listOf("c1", "c2", "c3"), setOf("c1", "c2", "c3"), emptyList(), emptyList(), true,
            owned = 3, restored = 3, failed = 0, ifwMinus = setOf("c1", "c2", "c3"), pmMinus = emptySet(),
        ),
        // IFW 规则文件改写失败：三个都不算恢复，IFW 桶原样保留
        Off(
            "ifw rewrite failed", listOf("c1", "c2", "c3"), setOf("c1", "c2", "c3"), emptyList(), emptyList(), false,
            owned = 3, restored = 0, failed = 3, ifwMinus = emptySet(), pmMinus = emptySet(), allFailed = true,
        ),
        // pm enable 失败：IFW 那半边成功，按部分恢复
        Off(
            "pm enable failed", listOf("c1", "c2"), setOf("c1"), listOf("c2"), bad("c2"), true,
            owned = 2, restored = 1, failed = 1, ifwMinus = setOf("c1", "c2"), pmMinus = emptySet(), partial = true,
        ),
        // 回执缺失（per-item 入口校验异常时返回空表）按失败计
        Off(
            "pm receipts missing", listOf("c1", "c2"), setOf("c1"), listOf("c2"), emptyList(), true,
            owned = 2, restored = 1, failed = 1, ifwMinus = setOf("c1", "c2"), pmMinus = emptySet(), partial = true,
        ),
        // 双桶重叠：pm 已启用但 IFW 规则没删掉——组件仍被拦着，算失败；PM 桶撤、IFW 桶留（批Y4b）
        Off(
            "overlap, pm ok + ifw failed", listOf("c1"), setOf("c1"), listOf("c1"), ok("c1"), false,
            owned = 1, restored = 0, failed = 1, ifwMinus = emptySet(), pmMinus = setOf("c1"), allFailed = true,
        ),
        // 双桶重叠：IFW 删掉了但 pm 没启用——IFW 桶撤、PM 桶留
        Off(
            "overlap, ifw ok + pm failed", listOf("c1"), setOf("c1"), listOf("c1"), bad("c1"), true,
            owned = 1, restored = 0, failed = 1, ifwMinus = setOf("c1"), pmMinus = emptySet(), allFailed = true,
        ),
        // 从未写入的组件（provider/native 不在账本里）不计入失败——设备上 43 记成 51 的那个口径问题
        Off(
            "never-written comps not counted", listOf("c1", "c2", "provider9"), setOf("c1"), listOf("c2"), ok("c2"), true,
            owned = 2, restored = 2, failed = 0, ifwMinus = setOf("c1", "c2", "provider9"), pmMinus = setOf("c2"),
        ),
        // 不在目标里的回执不撤账
        Off(
            "receipt for non-target ignored", listOf("c1", "c2"), setOf("c1"), listOf("c2"), ok("c9"), true,
            owned = 2, restored = 1, failed = 1, ifwMinus = setOf("c1", "c2"), pmMinus = emptySet(), partial = true,
        ),
        // 多项里一成功一失败一缺回执
        Off(
            "pm partially failed", listOf("c1", "c2", "c3"), emptySet(), listOf("c1", "c2", "c3"), ok("c1") + bad("c3"), true,
            owned = 3, restored = 1, failed = 2, ifwMinus = setOf("c1", "c2", "c3"), pmMinus = setOf("c1"), partial = true,
        ),
        // 账本里没有这个应用的任何记录：没有"本工具负责"的组件，按成功
        Off(
            "nothing owned", listOf("c1"), emptySet(), emptyList(), emptyList(), true,
            owned = 0, restored = 0, failed = 0, ifwMinus = setOf("c1"), pmMinus = emptySet(),
        ),
    )

    @Test
    fun `sdk-off accounting table`() {
        for (c in offCases) {
            val r = RestoreAccounting.sdkOff(
                RestoreAccounting.SdkOffInput(c.removed, c.ifwOwned, c.pmTargets, c.receipts, c.ifwOk)
            )
            assertEquals(c.name, c.owned, r.ownedTargets.size)
            assertEquals(c.name, c.restored, r.restoredOwned)
            assertEquals(c.name, c.failed, r.failedCount)
            assertEquals(c.name, c.ifwMinus, r.ifwRestored)
            assertEquals(c.name, c.pmMinus, r.pmRestored)
            assertEquals(c.name, c.allFailed, r.allFailed)
            assertEquals(c.name, c.partial, r.partial)
        }
    }

    private fun appRestore(
        targets: List<String>,
        receipts: List<Pair<String, Boolean>>,
        ifwOk: Boolean = true,
    ) = RestoreAccounting.appRestore(RestoreAccounting.AppRestoreInput(targets, receipts, ifwOk))

    @Test
    fun `app restore counts failed and missing receipts as failures`() {
        // 没有 pm 桶 / 全部启用成功
        assertEquals(RestoreAccounting.AppRestoreResult(0, true), appRestore(emptyList(), emptyList()))
        assertEquals(RestoreAccounting.AppRestoreResult(0, true), appRestore(listOf("c1", "c2"), ok("c1", "c2")))
        // 逐项失败与回执缺失都进失败数；整体失败
        assertEquals(RestoreAccounting.AppRestoreResult(1, false), appRestore(listOf("c1", "c2"), ok("c1") + bad("c2")))
        assertEquals(RestoreAccounting.AppRestoreResult(2, false), appRestore(listOf("c1", "c2"), emptyList()))
        // IFW 移除失败时 pm 全成功也算整体失败
        assertEquals(RestoreAccounting.AppRestoreResult(0, false), appRestore(listOf("c1"), ok("c1"), ifwOk = false))
    }

    private fun bucket(comps: List<String>, rules: Set<String> = setOf("sdk.a")) =
        AppliedRulesStore.EngineChange(
            components = comps,
            types = comps.associateWith { "activity" },
            preExisting = listOf("pre1"),
            ruleIds = rules,
        )

    @Test
    fun `bucket subtraction drops restored comps and the sdk intent`() {
        val b = bucket(listOf("c1", "c2"), setOf("sdk.a", "sdk.b"))
        val r = RestoreAccounting.minusBucket(b, setOf("c1"), "sdk.a")!!
        assertEquals(listOf("c2"), r.components)
        assertEquals(mapOf("c2" to "activity"), r.types)
        assertEquals(setOf("sdk.b"), r.ruleIds)
    }

    @Test
    fun `bucket with nothing left yields null so the entry is deleted`() {
        assertNull(RestoreAccounting.minusBucket(bucket(listOf("c1", "c2")), setOf("c1", "c2"), "sdk.a"))
        assertNull(RestoreAccounting.minusBucket(null, setOf("c1"), "sdk.a"))
    }

    @Test
    fun `bucket keeps the entry while another sdk still has intent`() {
        val r = RestoreAccounting.minusBucket(bucket(listOf("c1"), setOf("sdk.a", "sdk.b")), setOf("c1"), "sdk.a")!!
        assertEquals(emptyList<String>(), r.components)
        assertEquals(setOf("sdk.b"), r.ruleIds)
    }

    @Test
    fun `pre-existing comps are dropped from the bucket once restored`() {
        val b = bucket(listOf("c1")).copy(preExisting = listOf("c1"))
        assertNull(RestoreAccounting.minusBucket(b, setOf("c1"), "sdk.a"))
    }
}
