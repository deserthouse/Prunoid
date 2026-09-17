package io.github.deserthouse.sdkpruner.core.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.deserthouse.sdkpruner.core.rules.RuleRepository
import io.github.deserthouse.sdkpruner.core.rules.Safety
import io.github.deserthouse.sdkpruner.core.rules.safety

data class ScannedApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val matchedSdks: List<SdkHit>
)

data class SdkHit(
    val ruleId: String,
    val name: String,
    val category: String,
    val safety: Safety,
    val matchedComponents: List<String>,   // 该 app manifest 中命中的组件锚点
    val componentTypes: Map<String, String> = emptyMap()  // 组件全类名 -> 类型（activity/service/receiver）
)

// M1-R3 扫描器：PackageManager 枚举第三方 app + 四类组件，按规则匹配 SDK
class Scanner(
    context: Context,
    private val rules: RuleRepository
) {
    private val pm = context.packageManager

    private val componentIndex: Map<String, Pair<String, String>> by lazy {
        // 组件全类名 -> (规则id, 类型)；跨 app 直接查表
        val m = HashMap<String, Pair<String, String>>()
        for (r in rules.snapshot.sdks) {
            for (c in r.components) {
                if (c.type != "native") m[c.`class`] = r.id to c.type
            }
        }
        m
    }

    fun scanAll(): List<ScannedApp> {
        val out = mutableListOf<ScannedApp>()
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.packageName == "io.github.deserthouse.sdkpruner") continue
            val comps: List<String> = try {
                val pkg = pm.getPackageInfo(app.packageName, flags)
                val names = mutableListOf<String>()
                pkg.activities?.let { names.addAll(it.map { a -> a.name }) }
                pkg.services?.let { names.addAll(it.map { s -> s.name }) }
                pkg.receivers?.let { names.addAll(it.map { r -> r.name }) }
                pkg.providers?.let { names.addAll(it.map { p -> p.name }) }
                names
            } catch (_: Exception) {
                emptyList()
            }
            out.add(scanOne(app, comps))
        }
        return out.sortedWith(compareByDescending<ScannedApp> { it.matchedSdks.size }.thenBy { it.label })
    }

    fun scanOne(app: ApplicationInfo, components: List<String>): ScannedApp {
        val hits = LinkedHashMap<String, SdkHit>()
        // 1) 包名前缀匹配（最长前缀优先展示）
        for (r in rules.match(app.packageName)) {
            hits[r.id] = SdkHit(r.id, r.name, r.category, r.safety(), emptyList())
        }
        // 2) 组件全类名锚点匹配（记录类型，供 IFW 分组写入）
        for (cn in components) {
            componentIndex[cn]?.let { (rid, type) ->
                val r = rules.rule(rid) ?: return@let
                val prev = hits[rid]
                if (prev == null) {
                    hits[rid] = SdkHit(rid, r.name, r.category, r.safety(), listOf(cn), mapOf(cn to type))
                } else if (cn !in prev.matchedComponents) {
                    hits[rid] = prev.copy(
                        matchedComponents = prev.matchedComponents + cn,
                        componentTypes = prev.componentTypes + (cn to type)
                    )
                }
            }
        }
        return ScannedApp(
            packageName = app.packageName,
            label = app.loadLabel(pm).toString(),
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            matchedSdks = hits.values.sortedWith(
                compareBy<SdkHit> { it.safety.ordinal }.thenByDescending { it.matchedComponents.size }
            )
        )
    }
}
