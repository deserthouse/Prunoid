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
        try {
            if (!configAllows(context)) {
                Log.d("SdkPruner", "auto-reapply: config does not allow, skip $pkg")
                return@runBlocking
            }
            val store = AppliedRulesStore(context)
            val entry = store.get(pkg)
            if (entry == null) {
                Log.d("SdkPruner", "auto-reapply: no applied record for $pkg")
                return@runBlocking
            }
            Log.d("SdkPruner", "auto-reapply: reapplying ${entry.engine} rules for $pkg (rules=${entry.ruleIds.size})")
            val rules = io.github.deserthouse.prunoid.core.rules.RuleRepository(context)
            val scanner = io.github.deserthouse.prunoid.core.scanner.Scanner(context, rules)
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            val comps = DisableEngine.enumerateComponents(context, pkg)
            val compNames = comps.map { it.first }.toSet()
            val scanned = scanner.scanOne(appInfo, comps)

            // ① 保留：账本内组件若仍存在于最新 manifest（规则退订/更新后用户已禁用的选择不丢）
            val byType = entry.types.entries
                .filter { it.key in compNames }
                .groupBy({ it.key }, { it.key })
                .toMutableMap()

            // ② 增量：仅账本 ruleIds 范围内的 SDK，其当前 SAFE/CAUTION 命中新组件才纳入
            if (entry.ruleIds.isNotEmpty()) {
                scanned.matchedSdks
                    .filter { it.ruleId in entry.ruleIds && (it.safety == Safety.CAUTION || it.safety == Safety.SAFE) }
                    .flatMap { it.componentTypes.entries }
                    .groupBy({ it.value }, { it.key })
                    .forEach { (t, classes) ->
                        byType[t] = (byType[t].orEmpty() + classes).distinct()
                    }
            }

            if (byType.isEmpty()) {
                Log.d("SdkPruner", "auto-reapply: nothing to apply for $pkg, keeping ledger")
                return@runBlocking
            }

            val engine = DisableEngine(context, rules)
            when (entry.engine) {
                "IFW" -> engine.applyIfw(pkg, byType).getOrThrow()
                "PM" -> engine.applyPm(pkg, byType.values.flatten()).getOrThrow()
            }
            // 批X2（审查 R06）：写入成功才记账（merge 语义）
            store.recordMerge(
                pkg, entry.engine,
                byType.values.flatten(),
                byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap()
            )
            Log.d("SdkPruner", "auto-reapply: done $pkg (${byType.values.sumOf { it.size }} comps)")
        } catch (e: Exception) {
            Log.e("SdkPruner", "auto-reapply failed for $pkg", e)
        }
        Unit
    }

    fun enumerateComponents(context: Context, pkg: String): List<Pair<String, String>> =
        DisableEngine.enumerateComponents(context, pkg)
}
