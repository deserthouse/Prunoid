package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.deserthouse.prunoid.core.rules.SettingsRepository
import kotlinx.coroutines.flow.first
import io.github.deserthouse.prunoid.core.rules.Safety

// M3-R8：安装/更新自动重应用核心逻辑（Receiver 与前台服务共用）。
// 批X2（审查 R05）：范围收紧——只重应用「用户曾选过的 SDK」（账本 ruleIds）＋其新组件；
// 账本无 ruleIds 的旧条目只保留既有组件，不再全量 SAFE/CAUTION 扩展。
// 批X3（审查 R10/PN-02）：执行前复核三元组（autoReapply + root + realtime）——模式切换即刻约束后台。
object AutoReapply {

    // 热修 0.18.1：PACKAGE_REPLACED 会同时投递动态（守护服务）与静态两个 receiver——
    // 并发 process() 曾在同一包的 IFW .tmp 路径上互踩（一方 mv 先走，另一方报 ifw write failed）。
    // 进程级单飞：同包重应用串行化，后到者拿最新账本重算，不与前者交错读写。
    private val gate = Mutex()

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
        gate.withLock { processLocked(context, pkg) }
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
            Log.d("SdkPruner", "auto-reapply: reapplying ${entry.engine} rules for $pkg (rules=${entry.ruleIds.size})")
            val rules = io.github.deserthouse.prunoid.core.rules.RuleRepository(context)
            val scanner = io.github.deserthouse.prunoid.core.scanner.Scanner(context, rules)
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            val comps = DisableEngine.enumerateComponents(context, pkg)
            val compNames = comps.map { it.first }.toSet()
            val scanned = scanner.scanOne(appInfo, comps)

            // ① 保留：账本内组件若仍存在于最新 manifest（规则退订/更新后用户已禁用的选择不丢）。
            // 热修 0.18.1：entry.types 是 组件→类型 映射，分组键必须是类型（it.value）——
            // 原误写 groupBy({it.key},{it.key}) 把类名当类型键，产出类名伪标签组（provider 漏入 IFW、账本被污染）。
            val byType = entry.types.entries
                .filter { it.key in compNames }
                .groupBy({ it.value }, { it.key })
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
                return
            }

            val engine = DisableEngine(context, rules)
            // 批Y2（架构评审 C3）：IFW 只写它支持的类型——provider 在执行/记账前剔除（同源）；
            // 全 provider=无可写目标，保留账本不空转
            val (writeByType, writeComps) = if (entry.engine == "IFW") {
                val eff = byType.filterKeys { it != "provider" }
                eff to eff.values.flatten()
            } else {
                byType to byType.values.flatten()
            }
            if (writeByType.isEmpty()) {
                Log.d("SdkPruner", "auto-reapply: nothing writable for $pkg, keeping ledger")
                return
            }
            when (entry.engine) {
                "IFW" -> engine.applyIfw(pkg, writeByType).getOrThrow()
                "PM" -> engine.applyPm(pkg, writeComps).getOrThrow()
            }
            // 批X2（审查 R06）：写入成功才记账（merge 语义）
            store.recordMerge(
                pkg, entry.engine,
                writeComps,
                writeByType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap()
            )
            Log.d("SdkPruner", "auto-reapply: done $pkg (${writeComps.size} comps)")
        } catch (e: Exception) {
            Log.e("SdkPruner", "auto-reapply failed for $pkg", e)
        }
    }

    fun enumerateComponents(context: Context, pkg: String): List<Pair<String, String>> =
        DisableEngine.enumerateComponents(context, pkg)
}
