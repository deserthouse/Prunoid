package io.github.deserthouse.prunoid.core.engine

/** 批Q1（B01）：批量执行核心——只消费冻结的 [RuleActionPlan]，按计划 engine 分派
 *  （IFW→applyIfw / PM→applyPm），逐目标调用并产出逐项结果；不重算目标集、不静默扩范围。
 *  引擎调用以 lambda 注入（生产=DisableEngine 方法引用，单测=伪引擎记录实际调用），
 *  合同测试据此断言真正调用的包/组件/引擎，而非只测 Planner。 */
internal object BulkPlanExecutor {

    /** 一次真实引擎调用记录（合同测试消费）。 */
    data class TargetCall(val pkg: String, val engine: Engine, val components: List<String>)

    data class TargetResult(val pkg: String, val ok: Boolean, val detail: String, val compWritten: Int)

    data class ExecutionResult(
        val engine: Engine,
        val ok: Int,
        val compWritten: Int,
        val touched: List<String>,
        val items: List<TargetResult>,
        val calls: List<TargetCall>,
    )

    /** 执行计划。guard 返回非 null = 该目标被拒（执行期二次复核，detail 记原因）。
     *  组件计数=计划 byType 展平去重（IFW 侧 Planner 已剔 provider/native，与账本同源）。 */
    suspend fun execute(
        plan: RuleActionPlan,
        applyIfw: suspend (String, Map<String, List<String>>) -> Result<Int>,
        applyPm: suspend (String, List<String>) -> Result<Int>,
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
                results += TargetResult(pa.packageName, false, blocked, 0)
                continue
            }
            val components = pa.byType.values.flatten().distinct()
            val call = TargetCall(pa.packageName, plan.engine, components)
            calls += call
            val r = when (plan.engine) {
                Engine.IFW -> applyIfw(pa.packageName, pa.byType)
                Engine.PM -> applyPm(pa.packageName, components)
            }
            if (r.isSuccess) {
                ok++
                compWritten += components.size
                touched += pa.packageName
                results += TargetResult(pa.packageName, true, "", components.size)
            } else {
                results += TargetResult(
                    pa.packageName, false,
                    r.exceptionOrNull()?.message?.take(120) ?: "write failed", 0,
                )
            }
        }
        return ExecutionResult(plan.engine, ok, compWritten, touched, results, calls)
    }
}
