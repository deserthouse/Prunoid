package io.github.deserthouse.prunoid.core.engine

import io.github.deserthouse.prunoid.ui.operations.OperationItem

/** 批Q1（B01）：批量执行核心——只消费冻结的 [RuleActionPlan]，按计划 engine 分派
 *  （IFW→applyIfw / PM→applyPmPerItem），逐目标调用并产出逐项结果；不重算目标集、不静默扩范围。
 *  引擎调用以 lambda 注入（生产=DisableEngine 方法引用，单测=伪引擎记录实际调用），
 *  合同测试据此断言真正调用的包/组件/引擎，而非只测 Planner。
 *  批T2（C02）：PM 改逐项执行——回执/账本只认实际成功组件（compWritten=实数，
 *  原按计划数冒报）；部分成功包入 touched 现场回读、detail 如实注明；IFW 按返回值钳制计数。 */
internal object BulkPlanExecutor {

    /** 一次真实引擎调用记录（合同测试消费）。 */
    data class TargetCall(val pkg: String, val engine: Engine, val components: List<String>)

    data class TargetResult(
        val pkg: String,
        val ok: Boolean,
        val detail: String,
        val compWritten: Int,
        /** 批T2（C02）：实际成功组件集（PM=逐项回执；IFW 成功=计划集）——账本与恢复只认此集。 */
        val okComponents: List<String>,
        /** 0.33.1：写入失败的组件——PM 部分失败=未成功的那些；IFW 整文件写入失败=该包全部计划组件；
         *  守卫拒绝（引擎未调用）与成功为空。结果卡据此列出失败组件（原来只有应用级的 "partial 46/51"）。 */
        val failedComponents: List<String> = emptyList(),
    )

    data class ExecutionResult(
        val engine: Engine,
        val ok: Int,
        val compWritten: Int,
        val touched: List<String>,
        val items: List<TargetResult>,
        val calls: List<TargetCall>,
    )

    /** 执行计划。guard 返回非 null = 该目标被拒（执行期二次复核，detail 记原因）。
     *  applyPm 为逐项执行回执（组件→成败）；applyIfw 返回实际写入计数。 */
    suspend fun execute(
        plan: RuleActionPlan,
        applyIfw: suspend (String, Map<String, List<String>>) -> Result<Int>,
        applyPm: suspend (String, List<String>) -> List<Pair<String, Boolean>>,
        guard: (String) -> String? = { pkg -> if (DisableEngine.isForbidden(pkg)) "protected" else null },
    ): ExecutionResult {
        var ok = 0
        var compWritten = 0
        val touched = mutableListOf<String>()
        val results = mutableListOf<TargetResult>()
        val calls = mutableListOf<TargetCall>()
        for (pa in plan.eligible) {
            val blocked = guard(pa.packageName)
            if (blocked != null) {
                results += TargetResult(pa.packageName, false, blocked, 0, emptyList())
                continue
            }
            val components = pa.byType.values.flatten().distinct()
            calls += TargetCall(pa.packageName, plan.engine, components)
            when (plan.engine) {
                Engine.IFW -> {
                    val r = applyIfw(pa.packageName, pa.byType)
                    if (r.isSuccess) {
                        ok++
                        // 批T2：计数按引擎返回值钳制（成功 0 写入不再冒报计划数）
                        val written = r.getOrDefault(0).coerceIn(0, components.size)
                        compWritten += written
                        touched += pa.packageName
                        results += TargetResult(pa.packageName, true, "", written, components)
                    } else {
                        results += TargetResult(
                            pa.packageName, false,
                            r.exceptionOrNull()?.message?.take(120) ?: "ifw write failed", 0, emptyList(),
                            failedComponents = components,
                        )
                    }
                }
                Engine.PM -> {
                    val perItem = applyPm(pa.packageName, components)
                    val okComps = perItem.filter { it.second }.map { it.first }
                    val allOk = okComps.size == components.size && components.isNotEmpty()
                    if (allOk) {
                        ok++
                        compWritten += okComps.size
                        touched += pa.packageName
                        results += TargetResult(pa.packageName, true, "", okComps.size, okComps)
                    } else if (okComps.isNotEmpty()) {
                        // 部分成功：包条目记失败+如实注明，但成功组件必须入账本与现场回读（有真实写入）
                        compWritten += okComps.size
                        touched += pa.packageName
                        results += TargetResult(
                            pa.packageName, false,
                            "partial ${okComps.size}/${components.size}", okComps.size, okComps,
                            failedComponents = components.filter { it !in okComps },
                        )
                    } else {
                        val why = perItem.firstOrNull { !it.second }?.let { _ -> "pm disable failed" } ?: "pm disable failed"
                        results += TargetResult(pa.packageName, false, why, 0, emptyList(), failedComponents = components)
                    }
                }
            }
        }
        return ExecutionResult(plan.engine, ok, compWritten, touched, results, calls)
    }

    /** 0.33.1：结果卡条目。失败/部分失败的应用在原因后逐行列出失败组件（明细底表可见）；
     *  超过 [max] 个只列前 [max] 个并标出剩余数（默认 60——覆盖常见规则集，如穿山甲 51 个组件）。
     *  成功条目的 detail 保持为空。 */
    fun resultItem(res: TargetResult, max: Int = 60): OperationItem {
        val failed = res.failedComponents
        val detail = if (res.ok || failed.isEmpty()) res.detail else buildString {
            append(res.detail)
            append('\n')
            failed.take(max.coerceAtLeast(1)).joinTo(this, "\n")
            if (failed.size > max) append("\n… +").append(failed.size - max)
        }
        return OperationItem(res.pkg, res.ok, detail)
    }

    // ── 0.33.1：账本写失败后的批量撤销 ────────────────────────────────────

    const val ROLLED_BACK = "ledger write failed, change rolled back"
    const val ROLLBACK_INCOMPLETE = "ledger write failed, rollback incomplete"

    /** 撤销结果。[undone]=已撤销的包数；[leftover]=仍有未记账变更的包；[detail]=各包的结果卡文案 */
    data class UndoOutcome(val undone: Int, val leftover: List<String>, val detail: Map<String, String>)

    /** 把"账本没记上"的包恢复到本次批量之前：IFW 规则文件写回原文；pm 只还原本次新造成的禁用，
     *  各组件回到写入前状态（写入前已禁用的外部状态不动）。写入前状态未知的包不动 pm、计入 leftover——
     *  分不清哪些是外部既有禁用，宁可如实报告"撤销未完成"也不贸然启用。
     *  引擎调用以 lambda 注入（生产=DisableEngine，单测=伪引擎），与 [execute] 同一做法。 */
    suspend fun undoUnrecorded(
        engine: Engine,
        unrecorded: List<TargetResult>,
        ifwBefore: Map<String, String>,
        pmBefore: Map<String, Map<String, String>?>,
        rollbackIfw: suspend (String, String) -> Boolean,
        restorePm: suspend (String, List<PmStateSnapshot.Action>) -> List<Pair<String, Boolean>>,
    ): UndoOutcome {
        var undone = 0
        val leftover = mutableListOf<String>()
        val detail = LinkedHashMap<String, String>()
        for (res in unrecorded) {
            val pkg = res.pkg
            val ok = when (engine) {
                Engine.IFW -> rollbackIfw(pkg, ifwBefore[pkg].orEmpty())
                Engine.PM -> {
                    val undo = RestoreAccounting.rollbackActions(pkg, res.okComponents, pmBefore[pkg])
                    undo != null && (undo.isEmpty() ||
                        restorePm(pkg, undo).let { rc -> rc.size == undo.size && rc.all { it.second } })
                }
            }
            detail[pkg] = if (ok) ROLLED_BACK else ROLLBACK_INCOMPLETE
            if (ok) undone++ else leftover += pkg
        }
        return UndoOutcome(undone, leftover, detail)
    }

    /** 批T1（C01）：PM 归属准备——外部已禁用 = 写前现场 ∩ 目标 − 本工具 PM 桶已有。
     *  与详情路径批Z3/RV-01 同语义：owned 是本工具自己的写入，重复 apply 不得污染成"外部既有状态"；
     *  批量路径此前完全缺失此步，恢复会错误启用外部已禁用组件。 */
    fun pmPreExisting(
        targets: Collection<String>,
        beforeDisabled: Set<String>,
        owned: Set<String>,
    ): List<String> = targets.filter { it in beforeDisabled && it !in owned }
}
