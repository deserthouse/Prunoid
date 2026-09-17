package io.github.deserthouse.prunoid.core.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.deserthouse.prunoid.core.rules.RuleRepository
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.rules.safety

data class ScannedApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val matchedSdks: List<SdkHit>,
    // 未被任何规则识别的组件：按 Java 包前缀聚类（LibChecker "Unmarked library" 语义），
    // suspicious = 前缀/类名含广告统计类特征词（仅提示，永不参与自动禁用）
    val unmatched: List<UnmatchedGroup> = emptyList()
)

data class UnmatchedGroup(
    val prefix: String,
    val count: Int,
    val suspicious: Boolean
)

// 启发式特征词（小写匹配）：只用于"疑似"标注，来源 oF2pks/AdClose 拆解经验
private val SUSPICIOUS_KEYWORDS = listOf(
    "ads", ".ad.", "admob", "adview", "adv.", "tracker", "track", "analytics",
    "applog", "umeng", "getui", "jpush", "jad", "gdt", "pangle", "sigmob",
    "mintegral", "applovin", "vungle", "ironsource", "unity3d.ads", "kwai", "adnet"
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
            if (app.packageName == "io.github.deserthouse.prunoid") continue
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

        val matchedCns = mutableSetOf<String>()
        for ((cn, manifestType) in components) {
            // ② 精确锚点（LCR 组件规则；anchor 携带上游标注类型）
            val anchor = componentIndex[cn]
            if (anchor != null) {
                matchedCns.add(cn)
                addHit(anchor.first, cn, anchor.second)
                continue
            }
            // ③ 前缀匹配（blocker/oF2pks searchKeyword 语义，类型取 manifest 实际值）
            val prefixRids = prefixMatcher.match(cn)
            if (prefixRids.isNotEmpty()) {
                matchedCns.add(cn)
                for (rid in prefixRids) addHit(rid, cn, manifestType)
            }
        }

        // 未识别组件：按 Java 包前缀聚类 + 启发式疑似标注（只展示，不参与禁用）
        val unmatched = components.asSequence()
            .filterNot { (cn, _) -> cn in matchedCns }
            .groupBy({ it.first.substringBeforeLast('.') }, { it.second })
            .map { (prefix, types) ->
                UnmatchedGroup(
                    prefix = prefix,
                    count = types.size,
                    suspicious = SUSPICIOUS_KEYWORDS.any { kw -> prefix.lowercase().contains(kw) }
                )
            }
            .sortedByDescending { it.count }
            .take(20)

        return ScannedApp(
            packageName = app.packageName,
            label = app.loadLabel(pm).toString(),
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            matchedSdks = hits.values.sortedWith(
                compareBy<SdkHit> { it.safety.ordinal }.thenByDescending { it.matchedComponents.size }
            ),
            unmatched = unmatched
        )
    }
}
