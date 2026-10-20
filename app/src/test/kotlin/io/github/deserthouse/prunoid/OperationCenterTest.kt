package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.ui.operations.OperationCenter
import io.github.deserthouse.prunoid.ui.operations.OperationItem
import io.github.deserthouse.prunoid.ui.operations.OperationKind
import io.github.deserthouse.prunoid.ui.operations.OperationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批FF-B1①：PM 逐项执行分块策略 + OperationCenter 结果判定（七审 AA7 / 方案B S1） */
class OperationCenterTest {

    @Test
    fun `全部成功走块级快路径`() {
        var calls = 0
        val out = OperationCenter.chunkedPerItem(listOf("a", "b", "c"), chunkSize = 2) { calls += 1; true }
        assertEquals(listOf("a" to true, "b" to true, "c" to true), out)
        assertEquals(2, calls) // 3 项按 2 分块=2 次块级调用，无逐条回退
    }

    @Test
    fun `失败块内逐条定位`() {
        // "bad" 所在块整体失败 → 块内逐条：bad 失败、其余成功
        val out = OperationCenter.chunkedPerItem(listOf("a", "bad", "c"), chunkSize = 3) { block ->
            block.all { it != "bad" }
        }
        assertEquals(
            listOf("a" to true, "bad" to false, "c" to true),
            out,
        )
    }

    @Test
    fun `空集返回空`() {
        val out = OperationCenter.chunkedPerItem(emptyList<String>()) { true }
        assertTrue(out.isEmpty())
    }

    @Test
    fun `结果判定四态`() {
        val c = OperationCenter()
        val ok = c.record(OperationKind.APPLY, "p", listOf(OperationItem("a", true)), "m")
        val fail = c.record(OperationKind.APPLY, "p", listOf(OperationItem("a", false), OperationItem("b", false)), "m")
        val partial = c.record(OperationKind.APPLY, "p", listOf(OperationItem("a", true), OperationItem("b", false)), "m")
        val unverified = c.record(OperationKind.APPLY, "p", emptyList(), "m")
        assertEquals(OperationOutcome.SUCCESS, ok.outcome)
        assertEquals(OperationOutcome.FAILED, fail.outcome)
        assertEquals(OperationOutcome.PARTIAL, partial.outcome)
        assertEquals(OperationOutcome.UNVERIFIED, unverified.outcome)
        assertEquals(4, c.operations.value.size)
        assertEquals(1, c.operations.value.first().id)
        assertEquals(4, c.operations.value.last().id) // operationId 单调（AV06 协议层）
    }
}
