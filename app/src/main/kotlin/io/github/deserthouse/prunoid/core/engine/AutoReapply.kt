package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.github.deserthouse.prunoid.core.rules.Safety

// M3-R8：安装/更新自动重应用核心逻辑（Receiver 与前台服务共用）
// 增量语义 = 重扫该包最新 manifest → 按当前规则重算 CAUTION+/SAFE 目标 → 与已应用旧目标并集
// （更新引入新组件自动纳入；已卸载组件自动剔除）
object AutoReapply {

    fun shouldHandle(context: Context, pkg: String?): Boolean {
        if (pkg == null || pkg == context.packageName) return false
        return !DisableEngine.isForbidden(pkg)
    }

    fun process(context: Context, pkg: String) = runBlocking(Dispatchers.IO) {
        try {
            val store = AppliedRulesStore(context)
            val entry = store.get(pkg)
            if (entry == null) {
                Log.d("SdkPruner", "auto-reapply: no applied record for $pkg")
                return@runBlocking
            }
            Log.d("SdkPruner", "auto-reapply: reapplying ${entry.engine} rules for $pkg")
            val rules = io.github.deserthouse.prunoid.core.rules.RuleRepository(context)
            val scanner = io.github.deserthouse.prunoid.core.scanner.Scanner(context, rules)
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            val comps = enumerateComponents(context, pkg)
            val compNames = comps.map { it.first }.toSet()
            val scanned = scanner.scanOne(appInfo, comps)
            // 新目标：当前规则下的 CAUTION+/SAFE 组件
            val byType = scanned.matchedSdks
                .filter { it.safety == Safety.CAUTION || it.safety == Safety.SAFE }
                .flatMap { it.componentTypes.entries }
                .groupBy({ it.value }, { it.key })
                .toMutableMap()
            // 并集：旧记录组件若仍存在于最新 manifest 则保留（规则退订后用户已应用的选择不丢）
            val present = entry.types.entries
                .filter { it.key in compNames }
                .groupBy({ it.value }, { it.key })
            for ((type, classes) in present) {
                val merged = (byType[type].orEmpty() + classes).distinct()
                if (merged.isNotEmpty()) byType[type] = merged
            }
            val engine = DisableEngine(context, rules)
            when (entry.engine) {
                "IFW" -> engine.applyIfw(pkg, byType)
                "PM" -> engine.applyPm(pkg, byType.values.flatten())
            }
            store.record(pkg, entry.engine, byType.values.flatten(), byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap())
            Log.d("SdkPruner", "auto-reapply: done $pkg (${byType.values.sumOf { it.size }} comps)")
        } catch (e: Exception) {
            Log.e("SdkPruner", "auto-reapply failed for $pkg", e)
        }
        Unit
    }

    fun enumerateComponents(context: Context, pkg: String): List<Pair<String, String>> {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        return runCatching {
            buildList {
                pm.getPackageInfo(pkg, flags)?.let {
                    it.activities?.let { a -> a.forEach { add(it.name to "activity") } }
                    it.services?.let { s -> s.forEach { add(it.name to "service") } }
                    it.receivers?.let { r -> r.forEach { add(it.name to "receiver") } }
                    it.providers?.let { p -> p.forEach { add(it.name to "provider") } }
                }
            }
        }.getOrDefault(emptyList())
    }
}
