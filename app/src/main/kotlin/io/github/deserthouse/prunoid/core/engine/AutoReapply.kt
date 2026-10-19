package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.github.deserthouse.prunoid.core.rules.SettingsRepository
import kotlinx.coroutines.flow.first
import io.github.deserthouse.prunoid.core.rules.Safety

// M3-R8：安装/更新自动重应用核心逻辑（Receiver 与前台服务共用）。
// 批X2（审查 R05）：范围收紧——只重应用「用户曾选过的 SDK」（账本 ruleIds）＋其新组件；
// 账本无 ruleIds 的旧条目只保留既有组件，不再全量 SAFE/CAUTION 扩展。
// 批X3（审查 R10/PN-02）：执行前复核三元组（autoReapply + root + realtime）——模式切换即刻约束后台。
object AutoReapply {

    // 批Z2：改用 OpCoordinator 全局写锁——与手动写入口同锁串行，
    // 后到者拿最新账本重算，不与前者交错读写（原私有 Mutex 已并入）。
    fun shouldHandle(context: Context, pkg: String?): Boolean {
        if (pkg == null || pkg == context.packageName) return false
        // 批X1（审查 R03）：前缀表 + 真实包属性双重判定
        return !DisableEngine.isForbidden(pkg) && !DisableEngine.isSystemPackage(context, pkg)
    }

    /** 批X3：每次执行前复核最新三元组——模式切换后旧 receiver/服务不得继续写入 */
    fun configAllows(context: Context): Boolean = runBlocking {
        val s = SettingsRepository(context).settings.first()
        s.autoReapply && s.workMode == "root" && s.reapplyMode == "realtime"
    }

    fun process(context: Context, pkg: String) = runBlocking(Dispatchers.IO) {
        OpCoordinator.write { processLocked(context, pkg) }
    }

    private suspend fun processLocked(context: Context, pkg: String) {
        try {
            if (!configAllows(context)) {
                Log.d("SdkPruner", "auto-reapply: config does not allow, skip $pkg")
                return
            }
            val store = AppliedRulesStore(context)
            val entry = store.get(pkg)
            if (entry == null) {
                Log.d("SdkPruner", "auto-reapply: no applied record for $pkg")
                return
            }
            Log.d("SdkPruner", "auto-reapply: reapplying for $pkg (ruleIds=${entry.ruleIds.size}, buckets=${listOfNotNull(entry.ifw?.let { "IFW" }, entry.pm?.let { "PM" }).joinToString("/")})")
            val rules = io.github.deserthouse.prunoid.core.rules.RuleRepository(context)
            val scanner = io.github.deserthouse.prunoid.core.scanner.Scanner(context, rules.published)
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            val comps = DisableEngine.enumerateComponents(context, pkg)
            val compNames = comps.map { it.first }.toSet()
            val scanned = scanner.scanOne(appInfo, comps)

            val engine = DisableEngine(context, rules)
            var wroteAny = false
            // 批Z1：按引擎桶各自重应用——v2 账本同包可含双桶，v1 迁移后单桶走同一循环。
            // ① 保留：桶内组件仍存在于最新 manifest；分组键=类型（0.18.1 热修 C1）。
            // ② 增量：批BB RV-02 改用桶内 ruleIds（跨引擎不串用；共享集迁移/桶内空=只保留不扩展）。
            for ((eng, ch) in listOfNotNull(
                entry.ifw?.let { "IFW" to it },
                entry.pm?.let { "PM" to it }
            )) {
                val byType = ch.types.entries
                    .filter { it.key in compNames }
                    .groupBy({ it.value }, { it.key })
                    .toMutableMap()
                var freshPreExisting: List<String> = emptyList()
                if (ch.ruleIds.isNotEmpty()) {
                    val freshClasses = scanned.matchedSdks
                        .filter { it.ruleId in ch.ruleIds && (it.safety == Safety.CAUTION || it.safety == Safety.SAFE) }
                        .flatMap { it.componentTypes.entries }
                        .groupBy({ it.value }, { it.key })
                    freshClasses.forEach { (t, classes) ->
                        if (eng == "IFW" && (t == "provider" || t == "native")) return@forEach
                        byType[t] = (byType[t].orEmpty() + classes).distinct()
                    }
                    // 批BB RV-01：PM 桶新增组件的所有权采集——写入前已禁用者记 preExisting
                    // （排除本桶已有组件，它们是本工具自己的写入不算外部既有）
                    if (eng == "PM") {
                        val owned = ch.components.toSet()
                        val freshComps = freshClasses.values.flatten().filter { it !in owned }
                        if (freshComps.isNotEmpty()) {
                            val pre = engine.readPmDisabled(pkg)
                            freshPreExisting = freshComps.filter { it in pre }
                        }
                    }
                }
                // Y2：IFW 只写它支持的类型（执行/记账同源）；全 provider 桶=无可写目标跳过
                val (writeByType, writeComps) = if (eng == "IFW") {
                    val eff = byType.filterKeys { it != "provider" && it != "native" }
                    eff to eff.values.flatten()
                } else {
                    byType to byType.values.flatten()
                }
                if (writeByType.isEmpty()) continue
                when (eng) {
                    "IFW" -> engine.applyIfw(pkg, writeByType).getOrThrow()
                    "PM" -> engine.applyPm(pkg, writeComps).getOrThrow()
                }
                // 批X2（审查 R06）：写入成功才记账（merge 语义，桶内合并）
                store.recordMerge(
                    pkg, eng,
                    writeComps,
                    writeByType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap(),
                    preExisting = freshPreExisting
                )
                wroteAny = true
                Log.d("SdkPruner", "auto-reapply: done $pkg [$eng] (${writeComps.size} comps)")
            }
            if (!wroteAny) Log.d("SdkPruner", "auto-reapply: nothing to apply for $pkg, keeping ledger")
        } catch (e: Exception) {
            Log.e("SdkPruner", "auto-reapply failed for $pkg", e)
        }
    }

    fun enumerateComponents(context: Context, pkg: String): List<Pair<String, String>> =
        DisableEngine.enumerateComponents(context, pkg)
}
