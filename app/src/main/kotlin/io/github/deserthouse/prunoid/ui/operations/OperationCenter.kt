package io.github.deserthouse.prunoid.ui.operations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** 批FF B1①（七审 AA7 / 方案B S1）：操作结果协议——
 *  operationId 身份 + 逐项成败集 + 四态结果；UI（结果卡/管理面）与回执同源消费。 */

enum class OperationKind { APPLY, RESTORE, DISABLE_EVERYWHERE, SET_SDK_FOR_APP, CLEAR_IFW }

enum class OperationOutcome { SUCCESS, PARTIAL, FAILED, UNVERIFIED }

/** 逐项结果：一个组件（或一个应用/一个子任务）的一次写入成败。 */
data class OperationItem(val target: String, val ok: Boolean, val detail: String = "")

/** 已完结操作记录（B2 起补 RUNNING/READING_LIVE 中间态与进度上报）。 */
data class Operation(
    val id: Int,
    val kind: OperationKind,
    val target: String,
    val outcome: OperationOutcome,
    val items: List<OperationItem>,
    val okCount: Int,
    val failCount: Int,
    val message: String,
    val at: Long,
    /** APPLY 类操作的重试参数（选中的 ruleId 集）——B2 结果卡重试钮消费。 */
    val retryRuleIds: Set<String> = emptySet(),
)

/**
 * 操作结果中心（AppViewModel 成员，生命周期=VM）。写路径完成后 record 一条；
 * 结果归属操作本身而非页面——离开页面不抹除，重复操作各占一条（AV06 协议层语义）。
 */
class OperationCenter {
    private val seq = AtomicInteger(0)
    private val _operations = MutableStateFlow<List<Operation>>(emptyList())
    val operations: StateFlow<List<Operation>> = _operations.asStateFlow()

    fun record(
        kind: OperationKind,
        target: String,
        items: List<OperationItem>,
        message: String,
        retryRuleIds: Set<String> = emptySet(),
    ): Operation {
        val ok = items.count { it.ok }
        val fail = items.size - ok
        val outcome = when {
            items.isEmpty() -> OperationOutcome.UNVERIFIED
            fail == 0 -> OperationOutcome.SUCCESS
            ok == 0 -> OperationOutcome.FAILED
            else -> OperationOutcome.PARTIAL
        }
        val op = Operation(
            id = seq.incrementAndGet(),
            kind = kind,
            target = target,
            outcome = outcome,
            items = items,
            okCount = ok,
            failCount = fail,
            message = message,
            at = System.currentTimeMillis(),
            retryRuleIds = retryRuleIds,
        )
        _operations.update { it + op }
        return op
    }

    companion object {
        /** 分块批量执行 → 逐项结果（快路径块级成功一次判定；失败块内逐条定位）。
         *  纯函数抽取供单测：exec(block) 由调用方注入（真机=Shell.cmd，单测=假实现）。 */
        fun <T> chunkedPerItem(
            components: List<T>,
            chunkSize: Int = 20,
            exec: (List<T>) -> Boolean,
        ): List<Pair<T, Boolean>> {
            val out = mutableListOf<Pair<T, Boolean>>()
            components.chunked(chunkSize.coerceAtLeast(1)).forEach { block ->
                if (exec(block)) {
                    block.forEach { out += it to true }
                } else {
                    block.forEach { c -> out += c to exec(listOf(c)) }
                }
            }
            return out
        }
    }
}
