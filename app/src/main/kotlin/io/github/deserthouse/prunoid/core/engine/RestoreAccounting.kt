package io.github.deserthouse.prunoid.core.engine

/** 0.32.1（第六轮审查 R6-02）：恢复记账算术——纯函数。
 *
 *  关闭 SDK 的分桶记账/计数（批Y4/Y4b）与整应用恢复的回执缺失处理（批Y6）原先写在
 *  AppViewModel 的协程体里，依赖 Android、无法 JVM 测试，正确性只有设备实证。这里把
 *  "给定命中组件、账本桶、逐项回执、IFW 改写结果 → 各桶应减掉哪些组件、成功/失败各多少"
 *  抽成可表驱动测试的函数；VM 只负责取数、调用引擎与落账。 */
internal object RestoreAccounting {

    /** pm enable 逐项回执：组件 → 是否成功；缺失的回执一律按失败计 */
    data class SdkOffInput(
        /** 本次撤掉的命中组件（IFW 规则文件改写范围） */
        val removed: Collection<String>,
        /** 账本 IFW 桶当前负责的组件 */
        val ifwOwned: Set<String>,
        /** 账本 PM 桶负责、且非外部预存的组件（本次 pm enable 的目标） */
        val pmTargets: Collection<String>,
        val pmReceipts: List<Pair<String, Boolean>>,
        /** IFW 规则文件改写是否成功 */
        val ifwOk: Boolean,
    )

    data class SdkOffResult(
        /** 本工具实际负责、本次应恢复的组件——失败/部分文案的计数口径（批Y4） */
        val ownedTargets: Set<String>,
        val restoredOwned: Int,
        val failedCount: Int,
        /** 应从 IFW 桶减掉的组件（仅当 IFW 改写成功） */
        val ifwRestored: Set<String>,
        /** 应从 PM 桶减掉的组件（按逐项成功回执） */
        val pmRestored: Set<String>,
    ) {
        /** 全失败：账本原样保留，恢复依据不丢 */
        val allFailed: Boolean get() = failedCount > 0 && restoredOwned == 0
        /** 部分失败：成功的组件撤账，失败的保持禁用并入可重试明细 */
        val partial: Boolean get() = failedCount > 0 && restoredOwned > 0
    }

    /** 关闭 SDK：哪些组件算"本工具负责"、其中多少已恢复、各桶减掉什么 */
    fun sdkOff(i: SdkOffInput): SdkOffResult {
        val targets = i.pmTargets.toSet()
        val pmOk = i.pmReceipts.filter { it.second }.map { it.first }.toSet()
        // 批Y4：按桶记账——IFW 桶只在其文件改写成功时才减组件，PM 桶按逐项回执减；
        // 同一组件被两桶共同负责时，两边都成功才算恢复（此前只要 pm 回执成功就把组件从两个桶里
        // 一起减掉——IFW 规则其实还留在盘上，成了没有恢复记录的孤儿规则）
        val ifwRestored = if (i.ifwOk) i.removed.toSet() else emptySet()
        val pmRestored = targets.intersect(pmOk)
        val owned = i.removed.filter { it in i.ifwOwned || it in targets }.toSet()
        fun restored(c: String) = (c !in i.ifwOwned || i.ifwOk) && (c !in targets || c in pmOk)
        val restoredOwned = owned.count { restored(it) }
        return SdkOffResult(owned, restoredOwned, owned.size - restoredOwned, ifwRestored, pmRestored)
    }

    /** 本次操作新造成的禁用 = 执行成功集里、写前现场并未停用的组件（账本写失败时据此撤销） */
    fun newlyDisabled(okComps: Collection<String>, disabledBefore: Set<String>): List<String> =
        okComps.filter { it !in disabledBefore }.distinct()

    data class AppRestoreInput(
        val pmTargets: Collection<String>,
        val pmReceipts: List<Pair<String, Boolean>>,
        /** IFW 规则移除是否成功（账本没有 IFW 桶时调用方按成功传入） */
        val ifwOk: Boolean,
    )

    data class AppRestoreResult(val pmFailCount: Int, val ok: Boolean)

    /** 整应用恢复：pm 逐项回执折成失败数；IFW 或 pm 任一失败即整体失败（账本不删） */
    fun appRestore(i: AppRestoreInput): AppRestoreResult {
        // 批Y6（R5-17）：回执缺失按失败计——per-item 入口在校验异常时返回空表，
        // "零回执"不能当"零失败"，否则组件仍停用时就把账本删了
        val pmFail = i.pmTargets.size - i.pmReceipts.count { it.second }
        return AppRestoreResult(pmFail, i.ifwOk && pmFail == 0)
    }

    /** 桶减掉已恢复组件与该 SDK 的意图；组件与意图都空了返回 null（双桶全空=删除条目，修 R04-b 复活问题） */
    fun minusBucket(
        b: AppliedRulesStore.EngineChange?,
        restored: Set<String>,
        ruleId: String,
    ): AppliedRulesStore.EngineChange? =
        b?.copy(
            components = b.components.filter { it !in restored },
            types = b.types.filterKeys { it !in restored },
            preExisting = b.preExisting.filter { it !in restored },
            // 批BB RV-02：关闭 SDK 同步终止该桶内对此 SDK 的意图
            ruleIds = b.ruleIds - ruleId,
        )?.takeIf { it.components.isNotEmpty() || it.ruleIds.isNotEmpty() }
}
