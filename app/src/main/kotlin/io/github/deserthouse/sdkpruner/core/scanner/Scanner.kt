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

// M1-R3 扫描器：PackageManager 枚举第三方 app + 四类组件，三路匹配：
// ① app 包名前缀 ② 组件全类名精确锚点（LCR）③ 组件类名前缀（blocker/oF2pks 的 searchKeyword 语义）
class Scanner(
    context: Context,
    private val rules: RuleRepository
) {
    private val pm = context.packageManager

    private val componentIndex: Map<String, Pair<String, String>> by lazy {
        val m = HashMap<String, Pair<String, String>>()
        for (r in rules.effectiveRules) {
            for (c in r.components) {
                if (c.type != "native") m[c.`class`] = r.id to c.type
            }
        }
        m
    }

    private val prefixMatcher: PrefixMatcher by lazy { PrefixMatcher(rules.effectiveRules) }

    fun scanAll(): List<ScannedApp> {
        val out = mutableListOf<ScannedApp>()
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.packageName == "io.github.deserthouse.sdkpruner") continue
            val comps: List<Pair<String, String>> = try {
                val pkg = pm.getPackageInfo(app.packageName, flags)
                val pairs = mutableListOf<Pair<String, String>>()
                pkg.activities?.let { names -> names.forEach { pairs.add(it.name to "activity") } }
                pkg.services?.let { names -> names.forEach { pairs.add(it.name to "service") } }
                pkg.receivers?.let { names -> names.forEach { pairs.add(it.name to "receiver") } }
                pkg.providers?.let { names -> names.forEach { pairs.add(it.name to "provider") } }
                pairs
            } catch (_: Exception) {
                emptyList()
            }
            out.add(scanOne(app, comps))
        }
        return out.sortedWith(compareByDescending<ScannedApp> { it.matchedSdks.size }.thenBy { it.label })
    }

    fun scanOne(app: ApplicationInfo, components: List<Pair<String, String>>): ScannedApp {
        val hits = LinkedHashMap<String, SdkHit>()
        fun addHit(rid: String, cn: String?, type: String?) {
            val r = rules.rule(rid) ?: return
            val prev = hits[rid]
            when {
                prev == null -> hits[rid] = SdkHit(
                    rid, r.name, r.category, r.safety(),
                    listOfNotNull(cn), if (cn != null && type != null) mapOf(cn to type) else emptyMap()
                )
                cn != null && cn !in prev.matchedComponents -> hits[rid] = prev.copy(
                    matchedComponents = prev.matchedComponents + cn,
                    componentTypes = prev.componentTypes + (cn to (type ?: prev.componentTypes[cn].orEmpty()))
                )
            }
        }

        // ① app 包名前缀匹配（app 本身就是 SDK 附属包的罕见场景）
        for (r in rules.match(app.packageName)) addHit(r.id, null, null)

        for ((cn, manifestType) in components) {
            // ② 精确锚点（LCR 组件规则；anchor 携带上游标注类型）
            val anchor = componentIndex[cn]
            if (anchor != null) {
                addHit(anchor.first, cn, anchor.second)
                continue
            }
            // ③ 前缀匹配（blocker/oF2pks searchKeyword 语义，类型取 manifest 实际值）
            for (rid in prefixMatcher.match(cn)) addHit(rid, cn, manifestType)
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
