package io.github.deserthouse.prunoid.core.engine

import io.github.deserthouse.prunoid.core.scanner.ScannedApp

/** 批M3（F02）：管理页批量动作的执行计划——UI/确认/执行/结果共同消费同一份不可变快照。
 *  原缺陷：确认对话框与按钮使用扫描命中数（79），而 disableSdkEverywhere 内部还要过滤
 *  系统保护/provider/native——极端样本（Jetpack App Startup 79 命中）确认数与实际执行集
 *  不同源，provider-only 规则走到确认后才报零目标。 */
enum class SkipReason(val key: String) {
    PROTECTED("protected"),          // 安全层保护前缀（硬拦截）
    SYSTEM("system"),                // 系统预装包（FLAG_SYSTEM）
    NO_WRITABLE_COMPONENTS("nowritable"), // 命中组件全部不被引擎支持（IFW 的 provider/native）
}

/** 单个可执行目标：app + 引擎可写组件（按类型分组）。 */
data class PlannedApp(
    val packageName: String,
    val label: String,
    val byType: Map<String, List<String>>,
) {
    val componentCount: Int get() = byType.values.sumOf { it.size }
}

/** 被跳过的命中：app + 原因（逐类计数供确认框呈现）。 */
data class SkippedApp(
    val packageName: String,
    val label: String,
    val reason: SkipReason,
)

/** 不可变执行计划快照。 */
data class RuleActionPlan(
    val ruleId: String,
    val engine: Engine,
    val hits: Int,                    // 扫描命中数（现状 UI 口径，保留作对照）
    val eligible: List<PlannedApp>,   // 可执行目标
    val skipped: List<SkippedApp>,    // 跳过明细（逐原因）
    val rulesGeneration: Long,
    /** 批Q1（B01）：计划冻结身份——执行与结果按此对账（同输入同值，构造期固化）。 */
    val planId: String = "",
) {
    val eligibleCount: Int get() = eligible.size
    val totalComponents: Int get() = eligible.sumOf { it.componentCount }
    fun skipCount(reason: SkipReason): Int = skipped.count { it.reason == reason }
}

/** 纯函数规划器（批M3）：无 Context/无 IO——同输入同输出，四类样本单测锁定。
 *  引擎安全层（DisableEngine.isForbidden 等）在执行时保留为二次复核，此处结果一致。 */
object RuleActionPlanner {

    /** 批M4（F03）：管理行现场状态四态——替代开关 any 聚合的单点 Boolean 表达
     *  （原缺陷：43/51 部分禁用也亮 On，被误读为"该 SDK 已完全关闭"）。 */
    enum class RowLive { UNKNOWN, NONE, PARTIAL, FULL }

    data class RowLiveInfo(val live: RowLive, val liveN: Int, val total: Int)

    fun classifyRowLive(total: Int, liveN: Int, loaded: Boolean): RowLiveInfo = when {
        !loaded -> RowLiveInfo(RowLive.UNKNOWN, 0, total)
        total <= 0 -> RowLiveInfo(RowLive.UNKNOWN, 0, total)
        liveN <= 0 -> RowLiveInfo(RowLive.NONE, 0, total)
        liveN >= total -> RowLiveInfo(RowLive.FULL, liveN, total)
        else -> RowLiveInfo(RowLive.PARTIAL, liveN, total)
    }

    fun planRuleAction(
        ruleId: String,
        engine: Engine,
        apps: List<ScannedApp>,
        rulesGeneration: Long = 0L,
    ): RuleActionPlan {
        val hits = apps.filter { a -> a.matchedSdks.any { it.ruleId == ruleId } }
        val eligible = mutableListOf<PlannedApp>()
        val skipped = mutableListOf<SkippedApp>()
        for (a in hits) {
            when {
                DisableEngine.isForbidden(a.packageName) ->
                    skipped += SkippedApp(a.packageName, a.label, SkipReason.PROTECTED)
                a.isSystem ->
                    skipped += SkippedApp(a.packageName, a.label, SkipReason.SYSTEM)
                else -> {
                    val hit = a.matchedSdks.first { it.ruleId == ruleId }
                    // Y2 口径：IFW 只写 activity/service/receiver；pm 支持全部四类
                    val grouped: Map<String, List<String>> = hit.componentTypes.entries
                        .groupBy({ it.value }, { it.key })
                    val byType: Map<String, List<String>> =
                        if (engine == Engine.IFW) grouped.filterKeys { it != "provider" && it != "native" }
                        else grouped
                    if (byType.isEmpty()) {
                        skipped += SkippedApp(a.packageName, a.label, SkipReason.NO_WRITABLE_COMPONENTS)
                    } else {
                        eligible += PlannedApp(
                            packageName = a.packageName,
                            label = a.label,
                            byType = byType.mapValues { (_, v) -> v.distinct().sorted() },
                        )
                    }
                }
            }
        }
        return RuleActionPlan(
            ruleId = ruleId,
            engine = engine,
            hits = hits.size,
            eligible = eligible,
            skipped = skipped,
            rulesGeneration = rulesGeneration,
            // 批Q1（B01）：构造期固化计划身份（规则代次+目标集指纹），执行/结果对账用
            planId = "${ruleId}@r${rulesGeneration}#" +
                (eligible.joinToString(",") { it.packageName + ":" + it.componentCount }).hashCode(),
        )
    }
}
