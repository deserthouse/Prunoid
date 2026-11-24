package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import io.github.deserthouse.prunoid.core.rules.RuleRepository
import io.github.deserthouse.prunoid.ui.operations.OperationCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// IFW XML 生成（纯函数，便于单测）——格式经 Blocker MIT 源码对照验证：
// <activity block="true" log="true"><component-filter name="pkg/cls"/></activity>
// provider 不受 IFW 支持，跳过
object IfwXmlBuilder {
    /** 解析现有 IFW XML → tag -> component-filter 全名集合（批L#13：XmlPullParser 正式解析，防正则误吞畸形文件） */
    fun parse(xml: String): Map<String, MutableSet<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        if (xml.isBlank()) return out
        try {
            val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(java.io.StringReader(xml))
            // 批N3：外层 tag（activity/service/receiver）分组——此前误用 component-filter
            // 自身作 key，产出 <component-filter> 单组 XML，IFW 系统不识别（静默失败）
            var currentTag: String? = null
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "activity", "service", "receiver" -> currentTag = parser.name
                            "component-filter" -> {
                                val name = parser.getAttributeValue(null, "name")
                                if (!name.isNullOrBlank() && currentTag != null) {
                                    out.getOrPut(currentTag!!) { linkedSetOf() }.add(name)
                                }
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        if (parser.name in setOf("activity", "service", "receiver")) currentTag = null
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // 畸形 XML 按已解析部分处理（调用方决定覆盖策略）
        }
        return out
    }

    /** 由 tag->filters 重建 XML；空则 null。
     *  批Y2（架构评审 C1/C3 加固）：IFW 仅认 activity/service/receiver——非法键（如
     *  账本污染产生的类名伪标签、native/provider 漏网）在构建边界丢弃，防畸形 XML */
    fun buildByTag(byTag: Map<String, Set<String>>): String? {
        val allowed = setOf("activity", "service", "receiver")
        fun xmlEscape(v: String): String = v
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val groups = byTag.mapNotNull { (tag, comps) ->
            if (tag !in allowed || comps.isEmpty()) return@mapNotNull null
            "  <$tag block=\"true\" log=\"true\">\n" +
                comps.sorted().joinToString("\n") { "    <component-filter name=\"${xmlEscape(it)}\" />" } +
                "\n  </$tag>"
        }
        if (groups.isEmpty()) return null
        // 批J6.3：文件必须以换行收尾——无尾换行时 readLiveDisabled 的 "echo ==IFW $p" 分节头
        // 会粘进上一文件末行（</rules>==IFW …），解析器按行前缀分节即漏掉后续整包（多包现场
        // 读把第二包条目全错归给第一包，实机 2/2 写入后 Oopz 行仍显未加载即此因）
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<rules>\n" +
            groups.joinToString("\n") + "\n</rules>\n"
    }

    fun build(byType: Map<String, List<String>>): String? {
        val groups = byType.mapNotNull { (type, comps) ->
            if (comps.isEmpty() || type == "provider") return@mapNotNull null
            val tag = when (type) {
                "service" -> "service"
                "receiver" -> "receiver"
                else -> "activity"
            }
            "  <$tag block=\"true\" log=\"true\">\n" +
                comps.joinToString("\n") { "    <component-filter name=\"$it\" />" } +
                "\n  </$tag>"
        }
        if (groups.isEmpty()) return null
        // 批J6.3：同 buildByTag——尾换行保证（读侧分节依赖）
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<rules>\n" +
            groups.joinToString("\n") + "\n</rules>\n"
    }
}

// M1-R4 禁用引擎：IFW 主 + pm disable 辅，含安全层（备份/恢复/系统白名单）
class DisableEngine(
    private val context: Context,
    private val rules: RuleRepository
) {
    companion object {
        init { Shell.enableVerboseLogging = false; Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10)) }

        /** 批BB RV-04：清 IFW 事务——三入口（VM/静态/动态 receiver）共用；
         *  全局写锁内执行，**成功才**终止 IFW 账本；失败原样保留恢复依据 */
        suspend fun clearIfwTransaction(context: android.content.Context): Result<Int> = OpCoordinator.write {
            val store = AppliedRulesStore(context)
            val eng = DisableEngine(context, RuleRepository(context))
            eng.clearAllIfw().mapCatching { n ->
                store.removeEngine(Engine.IFW.name)
                n
            }
        }

        // 系统/框架包白名单（M1 硬拦截；数据底座见 blocker-general-rules components/）
        val SYSTEM_PREFIXES = listOf(
            "android", "com.android.", "com.google.android.", "androidx.",
            "com.android.internal.", "miui", "com.miui.", "com.samsung.",
            "com.huawei.", "com.hihonor.", "com.oplus.", "com.coloros.",
            "com.vivo.", "com.xiaomi.", "org.chromium."
        )

        fun isForbidden(packageName: String): Boolean =
            packageName in SYSTEM_PREFIXES || SYSTEM_PREFIXES.any { packageName.startsWith(it) }

        // 批W5（M09）：包名/组件名白名单——壳插值与 XML 属性插值前统一防线（规则/账本数据
        // 可能含订阅来源内容）；合法字符集=Android 组件名（字母数字下划线美元+点），长度有界。
        // 校验在引擎公共入口执行，调用方（VM/规划器/订阅）无需各自重复。
        private val COMP_NAME_RE = Regex("^[A-Za-z0-9_$][A-Za-z0-9_$.]*$")
        private val PKG_NAME_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9_.]*\$")
        private const val COMP_NAME_MAX = 500
        internal fun validPkgName(n: String): Boolean = n.length <= 255 && PKG_NAME_RE.matches(n)
        internal fun validCompName(n: String): Boolean = n.length <= COMP_NAME_MAX && COMP_NAME_RE.matches(n)
        fun requireValidTarget(pkg: String, comps: Collection<String>) {
            require(validPkgName(pkg)) { "invalid package name" }
            // 热修 0.31.1（第五轮审查 R5-02）：空集合法——removeIfw(空集) 是"定向移除零项"的
            // 正常调用（恢复路径可达），0.31.0 把它连同非法名一并拒绝；写入入口的空目标
            // 由各自调用方按既有语义处理（applyIfw 原样重写/返回 0，PM 入口早退）
            require(comps.all { validCompName(it) }) { "invalid component name" }
        }

        // 批X1（审查 R03）：真实包属性判定——前缀表只是附加保护，不能代替 FLAG_SYSTEM
        private val systemFlagCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        fun isSystemPackage(context: Context, packageName: String): Boolean =
            systemFlagCache.getOrPut(packageName) {
                runCatching {
                    (context.packageManager.getApplicationInfo(packageName, 0).flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                }.getOrDefault(false)
            }

        fun isBlockedTarget(context: Context, packageName: String): Boolean =
            isForbidden(packageName) || isSystemPackage(context, packageName)

        /** 批X2（审查 R07）：统一组件枚举——必须含 MATCH_DISABLED_COMPONENTS，
         *  否则 PM 已禁用组件会从扫描消失，自动重应用误判"已卸载"而覆盖账本 */
        fun enumerateComponents(context: Context, pkg: String): List<Pair<String, String>> {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.MATCH_DISABLED_COMPONENTS
            return runCatching {
                buildList {
                    pm.getPackageInfo(pkg, flags)?.let {
                        it.activities?.forEach { a -> add(a.name to "activity") }
                        it.services?.forEach { sr -> add(sr.name to "service") }
                        it.receivers?.forEach { rc -> add(rc.name to "receiver") }
                        it.providers?.forEach { pr -> add(pr.name to "provider") }
                    }
                }
            }.getOrDefault(emptyList())
        }

        private fun ifwPath(pkg: String) = "/data/system/ifw/$pkg.xml"
        private const val IFW_DIR = "/data/system/ifw"

        /** 批K5：读脚本构造器独立可测——尾换行契约（IfwXmlBuilder）与本脚本分节空行是同一
         *  契约的写读两侧，任一侧回退即多包现场读丢包（批J6.3 P2）。测试锁定两处字符串形态。 */
        internal fun buildReadLiveScript(pkgs: List<String>, ifwDir: String = IFW_DIR): String = buildString {
            append("for p in")
            pkgs.forEach { append(" ").append(it) }
            // 批J6.3：头部前先 echo 空行——存量 IFW 文件可能无尾换行（</rules> 直接收尾），
            // 无空行时分节头粘进上一文件末行，按行前缀解析即丢包
            append("; do echo; echo \"==IFW \$p\"; cat ")
            append(ifwDir)
            append("/\$p.xml 2>/dev/null; done; ")
            append("echo ==TOTAL; grep -h -o component-filter ")
            append(ifwDir)
            append("/*.xml 2>/dev/null | wc -l")
        }
        private const val PKG_RESTRICTIONS = "/data/system/users/0/package-restrictions.xml"
    }

    // ── 批G：读现场——IFW 文件 + pm 停用状态的实际内容（展示层以现场为准，记账只喂恢复） ──
    /** 单次合并 shell 读取多个包的现场禁用集；返回 包名→被禁组件类名集合，及全库 IFW component-filter 总条数 */
    suspend fun readLiveDisabled(pkgs: List<String>): Pair<Map<String, Set<String>>, Int> = withContext(Dispatchers.IO) {
        if (pkgs.isEmpty()) return@withContext emptyMap<String, Set<String>>() to 0
        val script = buildReadLiveScript(pkgs)
        val out = Shell.cmd(script).exec().out
        android.util.Log.d("PrunoidLive", "pkgs=" + pkgs.size + " outLines=" + out.size + " scriptHead=" + script.take(120))
        val live = HashMap<String, MutableSet<String>>()
        var total = 0
        var cur: String? = null
        for (line in out) {
            when {
                line.startsWith("==IFW ") -> {
                    cur = line.removePrefix("==IFW ").trim()
                    if (cur.isNotEmpty()) live.getOrPut(cur) { mutableSetOf() }
                }
                line.startsWith("==TOTAL") -> cur = null
                cur != null -> {
                    // IFW xml：<component-filter name="pkg/.Cls"
                    val m = Regex("component-filter\\s+name=\"([^\"]+)\"").find(line) ?: continue
                    live.getOrPut(cur) { mutableSetOf() }.add(m.groupValues[1].substringAfter('/'))
                }
            }
        }
        // ==TOTAL 行取 wc 数
        out.lastOrNull()?.trim()?.toIntOrNull()?.let { total = it }
        // 批X2（审查 R13）：A16 实机采样定案——dumpsys package 不输出 disabledComponents/enabledComponents 段
        // （旧 sed 解析在 A16 全盲），PM 现场改走 PackageManager API 直查（应用侧 getComponentEnabledSetting，无 shell/无格式依赖）
        for (pkg in pkgs) {
            val pmDisabled = readPmDisabled(pkg)
            if (pmDisabled.isNotEmpty()) live.getOrPut(pkg) { mutableSetOf() }.addAll(pmDisabled)
        }
        android.util.Log.d("PrunoidLive", "parsed live=" + live.size + " total=" + total)
        live.mapValues { it.value.toSet() } to total
    }

    /** 批X2（审查 R13）：PM 现场直查——应用侧 getComponentEnabledSetting，无 shell/无 dumpsys 格式依赖。
     *  返回该包当前被 PM 禁用的组件全类名集（含 DISABLED / DISABLED_USER 两态）。 */
    /** 批Z3：PM 现场直查（公开）——applyPm 前读取"写入前已禁用"集（恢复所有权用） */
    suspend fun readPmDisabled(pkg: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching { readPmDisabledInternal(pkg) }.getOrDefault(emptySet())
    }

    private fun readPmDisabledInternal(pkg: String): Set<String> {
        val pm = context.packageManager
        return buildSet {
            for ((cls, _) in enumerateComponents(context, pkg)) {
                val st = runCatching {
                    pm.getComponentEnabledSetting(android.content.ComponentName(pkg, cls))
                }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                ) add(cls)
            }
        }
    }

    // ── 安全层：操作前全量备份（IFW 规则目录 + pm 组件限制状态，单个 tar 归档） ──
    suspend fun backup(keep: Int = 10): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = BackupPaths.dir(context.filesDir.absolutePath)
            Shell.cmd("mkdir -p $dir").exec()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = BackupPaths.file(context.filesDir.absolutePath, "backup_$ts.tar.gz")
            // 单次归档两个路径（-C / 用相对路径），失败则整体报错——不能静默丢一半
            val r = Shell.cmd(
                "tar -czf $out -C / $IFW_DIR $PKG_RESTRICTIONS"
            ).exec()
            val has = Shell.cmd("test -s $out").exec().isSuccess
            check(has) { "backup not created (tar rc=${r.code}, root ok?): ${r.err}" }
            // 批Z1（第五轮审查 R5-05）：pm 状态侧表——系统的状态文件在 Android 16 上由 fs-verity 保护、
            // root 也写不进，"替换文件"式还原不可能生效；备份时读出各组件启停状态另存为 JSON
            //（与归档同名的 .pm.json），还原时比对现状逐项执行 pm 命令。侧表写不出去=快照不完整
            val sideOk = runCatching {
                writeSidecar(PmStateSnapshot.sidecarPath(out), PmStateSnapshot.encode(collectPmStates()))
            }.getOrDefault(false)
            check(sideOk) { "pm state sidecar not written" }
            pruneBackups(dir, keep)
            out
        }
    }

    /** 备份保留策略：按文件名时间戳降序，只留最近 keep 份（用户可在设置中调）；侧表随归档一起清理 */
    private fun pruneBackups(dir: String, keep: Int) {
        val files = Shell.cmd("ls -1 $dir/backup_*.tar.gz 2>/dev/null").exec().out.toList().sortedDescending()
        // 批X4（审查 PN-15）：UI 允许 1~99999，引擎不再静默夹断到 3~30
        files.drop(keep.coerceAtLeast(1)).forEach {
            Shell.cmd("rm -f $it; rm -f ${PmStateSnapshot.sidecarPath(it)}").exec()
        }
    }

    // ── 批Z1：pm 状态侧表的采集与读写 ─────────────────────────────
    private val pmInfoFlags: Int = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS

    private fun stateName(st: Int): String = when (st) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DISABLED_USER"
        4 -> "DISABLED_UNTIL_USED"   // COMPONENT_ENABLED_STATE_DISABLE_UNTIL_USED（API 33 起废弃）
        else -> PmStateSnapshot.DEFAULT
    }

    /** 某个包全部组件的当前启停状态（含 DEFAULT）。包已卸载返回 null；其他读取失败向外抛。
     *  0.32.1（第六轮审查 R6-01）：此前任何异常都折成 null——回放时被当成"已卸载"静默跳过且
     *  不计失败，界面显示"均已还原"而该应用实际没还原、pm 记录还被清掉 */
    private fun readPmStatesOf(pkg: String): Map<String, String>? {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(pkg, pmInfoFlags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } ?: return null
        val comps = buildList {
            info.activities?.forEach { add(it.name to it) }
            info.services?.forEach { add(it.name to it) }
            info.receivers?.forEach { add(it.name to it) }
            info.providers?.forEach { add(it.name to it) }
        }
        return comps.associate { (name, ci) ->
            name to stateName(pm.getComponentEnabledSetting(android.content.ComponentName(ci.packageName, name)))
        }
    }

    /** 快照：全部第三方应用中非 DEFAULT 的组件状态（系统应用本工具不碰，不采集） */
    fun collectPmStates(): PmStateSnapshot.Sidecar {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var checked = 0
        val states = LinkedHashMap<String, Map<String, String>>()
        for (pi in context.packageManager.getInstalledPackages(pmInfoFlags)) {
            val app = pi.applicationInfo ?: continue
            if (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
            // 读取失败不跳过：缺了任何应用的状态，快照都会"看起来完整"——让备份如实失败
            val all = readPmStatesOf(pi.packageName) ?: continue
            checked += all.size
            val nonDefault = all.filterValues { it != PmStateSnapshot.DEFAULT }
            if (nonDefault.isNotEmpty()) states[pi.packageName] = nonDefault
        }
        android.util.Log.d("SdkPruner", "pm snapshot: ${states.size} packages with non-default state, $checked components checked in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        return PmStateSnapshot.Sidecar(takenAt = System.currentTimeMillis(), states = states)
    }

    /** 侧表经 root shell 以 base64 写入（backups 目录可能由 root 建立、应用自身无写权限）；先写 .tmp 再改名 */
    private fun writeSidecar(path: String, text: String): Boolean {
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val lines = b64.chunked(76).joinToString("\n")
        val tmp = "$path.tmp"
        return Shell.cmd(
            "base64 -d > $tmp <<'PRUNOID_B64'\n$lines\nPRUNOID_B64\nchmod 644 $tmp && mv -f $tmp $path"
        ).exec().isSuccess
    }

    /** 读回侧表（同样经 root shell，避免直接读 root 建立的文件被 SELinux 拒）；不存在或解不出返回 null */
    private fun readSidecar(path: String): PmStateSnapshot.Sidecar? {
        val r = Shell.cmd("test -f $path && base64 $path").exec()
        if (!r.isSuccess) return null
        val text = runCatching {
            String(java.util.Base64.getDecoder().decode(r.out.joinToString("")), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching { PmStateSnapshot.decode(text) }.getOrNull()
    }

    // ── 安全层：恢复备份（tar 解包回原路径 + 归属/上下文修复） ─────
    /** 批BB RV-05：结构化还原报告——账本同步按实际成功范围执行（RV-05：失败不得清桶）。
     *  批Z1：pmInArchive=归档带 pm 状态侧表；pmApplied/pmFailed=侧表回放的逐项结果 */
    data class RestoreReport(
        val ifwOk: Boolean, val pmOk: Boolean, val pmInArchive: Boolean,
        val pmApplied: Int = 0, val pmFailed: Int = 0,
    )

    suspend fun restoreBackup(backupPath: String): Result<RestoreReport> = withContext(Dispatchers.IO) {
        runCatching {
            // 批X2（审查 R11）：差异还原——纯解包不会删除备份后新建的 IFW 文件，恢复会"假成功"。
            // 流程：隔离目录解包 → 备份内的受管文件逐个还原 → 当前受管文件中备份没有的删除 → 校验。
            // 批BB RV-05：先探归档成员判定 PM 段；PM 恢复链去掉 || true（失败必须传播）。
            val stage = "/data/local/tmp/prunoid_restore"
            // 批W5（M09）+ 热修 0.31.1（第五轮审查 R5-01/R5-04）：备份路径白名单——路径必须
            // **恰为**本应用 backups 目录下的合法时点快照（BackupPaths 单一事实源，与 listBackups
            // 同源）。0.31.0 上 listBackups 返回裸文件名而此处要求绝对路径→应用内恢复必败；
            // 原 startsWith+containsMatchIn 判定还放行 backups/../x/… 穿越
            require(BackupPaths.isManaged(context.filesDir.absolutePath, backupPath)) {
                "backup path outside managed directory"
            }
            val pre = Shell.cmd(
                "rm -rf $stage && mkdir -p $stage && tar -xzf '$backupPath' -C $stage"
            ).exec()
            check(pre.isSuccess) { "restore unpack failed: ${pre.err}" }
            var ifwOk = true
            var pmOk = false
            // 批W5（M11）：stage 清理收进 finally——check/异常分支不再残留半成品
            try {
            val restore = StringBuilder("set -e")
            val ifwStage = "$stage$IFW_DIR"
            val backupFiles = Shell.cmd("ls $ifwStage/*.xml 2>/dev/null").exec().out
                .map { it.trim().substringAfterLast('/') }.filter { it.isNotEmpty() }
            if (backupFiles.isNotEmpty()) {
                restore.append("\nmkdir -p $IFW_DIR")
                backupFiles.forEach { f ->
                    restore.append("\ncp $ifwStage/$f $IFW_DIR/$f && chown system:system $IFW_DIR/$f && chmod 644 $IFW_DIR/$f")
                }
            }
            val current = Shell.cmd("ls $IFW_DIR/*.xml 2>/dev/null").exec().out
                .map { it.trim().substringAfterLast('/') }.filter { it.isNotEmpty() }
            (current - backupFiles.toSet()).forEach { extra ->
                restore.append("\nrm -f $IFW_DIR/$extra")
            }
            restore.append("\nrestorecon -R $IFW_DIR 2>/dev/null || true")
            val fin = Shell.cmd(restore.toString()).exec()
            ifwOk = fin.isSuccess
            check(ifwOk) { "restore apply failed: ${fin.err}" }
            Shell.cmd("rm -rf $stage").exec()
            // 批Z1（第五轮审查 R5-05）：pm 状态经侧表回放——不再把归档里的 package-restrictions.xml
            // 拷回去（Android 16 上该文件由系统以 fs-verity 保护，root 也写不进；即便拷得进去，
            // 系统内存里的状态也会在下次落盘时把它覆盖回来）。归档里没有侧表（0.31.2 及更早的备份）
            // = pm 状态不还原，如实报告。回放失败的项不吞：pmOk=false，调用方保留 pm 账本
            var pmInArchive = false
            var pmApplied = 0
            var pmFailed = 0
            val sidecar = readSidecar(PmStateSnapshot.sidecarPath(backupPath))
            if (sidecar != null) {
                pmInArchive = true
                // 0.32.1（第六轮审查 R6-04）：回放段单独兜底——IFW 规则此时已经替换到设备上，
                // 回放抛未预期异常时降级为 pmOk=false（pm 账本保留），不能让整个恢复报失败
                // 而账本没跟上已变的 IFW 现场
                runCatching {
                    val plan = PmStateSnapshot.planReplay(
                        sidecar,
                        // 侧表内容进 shell 命令前过引擎白名单（文件可能被改动）
                        allow = { validPkgName(it.pkg) && validCompName(it.comp) },
                        read = { readPmStatesOf(it) },
                    )
                    // R6-01：读取现状失败 ≠ 已卸载——该应用记为回放失败，不当成"无从设置"跳过
                    if (plan.readFailed > 0) {
                        android.util.Log.w("SdkPruner", "pm replay: current state unreadable for ${plan.readFailed} package(s)")
                    }
                    val receipts = OperationCenter.chunkedPerItem(plan.actions) { block ->
                        Shell.cmd(block.joinToString(" && ") { PmStateSnapshot.command(it) }).exec().isSuccess
                    }
                    val outcome = PmStateSnapshot.replayOutcome(receipts.count { it.second }, receipts.size, plan.readFailed)
                    pmApplied = outcome.applied
                    pmFailed = outcome.failed
                    android.util.Log.d("SdkPruner", "pm replay: commands=${plan.actions.size} applied=$pmApplied failed=$pmFailed")
                }.onFailure {
                    pmFailed = maxOf(pmFailed, 1)
                    android.util.Log.e("SdkPruner", "pm replay: aborted, pm state not restored", it)
                }
                pmOk = pmFailed == 0
            }
            RestoreReport(ifwOk = true, pmOk = pmOk, pmInArchive = pmInArchive, pmApplied = pmApplied, pmFailed = pmFailed)
            } finally {
                Shell.cmd("rm -rf $stage").exec()
            }
        }
    }

    /** 批X2（M07）：备份列举区分"空目录"与"读取失败"——原 2>/dev/null 掩盖一切失败，
     *  VM getOrDefault 再兜成空列表，UI 错误态永不可达。
     *  目录不存在=合法空态；目录存在但列举命令失败=错误（抛出走 VM runCatching）。 */
    fun listBackups(): List<String> {
        val dir = "${context.filesDir.absolutePath}/backups"
        val probe = Shell.cmd("test -d $dir").exec()
        if (!probe.isSuccess) return emptyList()
        val r = Shell.cmd("ls -1 $dir").exec()
        check(r.isSuccess) { "backup listing failed: ${r.err}" }
        // 热修 0.31.1（第五轮审查 R5-01）：返回绝对路径——还原白名单按绝对路径判定
        //（批X2 改成裸文件名后与批W5 白名单不同源，0.31.0 应用内恢复必败）
        return BackupPaths.fromListing(context.filesDir.absolutePath, r.out)
    }

    // ── 安全层：一键清除全部 IFW 规则（紧急恢复） ─────────────────
    suspend fun clearAllIfw(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val res = Shell.cmd("ls $IFW_DIR/*.xml 2>/dev/null").exec()
            val files = res.out.toList()
            if (files.isNotEmpty()) {
                // 批X2（审查 PN-05）：删除结果必须检查
                val rm = Shell.cmd("rm -f " + files.joinToString(" ")).exec()
                check(rm.isSuccess) { "ifw clear failed: rc=" + rm.code }
            }
            files.size
        }
    }


    // ── IFW 主引擎 ────────────────────────────────────────────────
    suspend fun applyIfw(pkg: String, byType: Map<String, List<String>>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireValidTarget(pkg, byType.values.flatten())
                // 批X4（审查 R09）：生产入口与 build() 对齐——provider 不受 IFW 支持且如实从计数剔除
                val filtered = byType.filterKeys { it != "provider" }
                require(!isBlockedTarget(context, pkg)) { "system app blocked by whitelist: $pkg" }
                val named = filtered.mapValues { (_, comps) -> comps.map { "$pkg/$it" } }
                // 批P0#8：合并语义——保留文件中既有 filter（含其他工具写入），叠加本次目标
                val existing = Shell.cmd("cat ${ifwPath(pkg)} 2>/dev/null").exec().out.joinToString("\n")
                val merged = linkedMapOf<String, MutableSet<String>>()
                IfwXmlBuilder.parse(existing).forEach { (tag, set) -> merged.getOrPut(tag) { linkedSetOf() }.addAll(set) }
                named.forEach { (tag, comps) -> merged.getOrPut(tag) { linkedSetOf() }.addAll(comps) }
                val xml = IfwXmlBuilder.buildByTag(merged)
                    ?: return@runCatching 0
                val tmp = "${context.cacheDir.absolutePath}/ifw_$pkg.xml"
                java.io.File(tmp).writeText(xml)
                // 批X2（审查 R06）：同目录 .tmp + mv 原子替换；全 && 链真实传播退出码（原 "; rm -f" 会掩盖失败）。
                // 热修 0.18.1：.tmp 名加纳秒后缀——cp/mv 链在并发写入（现由 AutoReapply 单飞互斥兜底）或
                // 异常残留同名 .tmp 时互踩，曾表现为一方 mv 先走另一方 "ifw write failed"。
                val stage = "${ifwPath(pkg)}.tmp.${System.nanoTime()}"
                try {
                    val r = Shell.cmd(
                        "mkdir -p $IFW_DIR && cp $tmp $stage && " +
                        "chmod 644 $stage && (restorecon $stage 2>/dev/null || true) && " +
                        "mv $stage ${ifwPath(pkg)}"
                    ).exec()
                    check(r.isSuccess) { "ifw write failed: ${r.err}" }
                } finally {
                    java.io.File(tmp).delete()
                    Shell.cmd("rm -f $stage").exec()
                }
                filtered.values.sumOf { it.size }
            }
        }

    suspend fun removeIfw(pkg: String, removeComponents: Set<String>? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireValidTarget(pkg, removeComponents.orEmpty())
                require(!isBlockedTarget(context, pkg)) { "system app blocked by whitelist: $pkg" }
                if (removeComponents != null) {
                    // 批J#1：参数语义修正（二轮实测删过头的根因=形参名 keep 实为删除集导致调用方传反）。
                    // 移除 removeComponents 的 filter，外部/其他来源的幸存
                    val existing = Shell.cmd("cat ${ifwPath(pkg)} 2>/dev/null").exec().out.joinToString("\n")
                    if (existing.isBlank()) return@runCatching
                    val remove = removeComponents.map { "$pkg/$it" }.toSet()
                    val rebuilt = IfwXmlBuilder.parse(existing).mapValues { (_, set) ->
                        set.filterNot { it in remove }.toSet()
                    }
                    val xml = IfwXmlBuilder.buildByTag(rebuilt)
                    if (xml == null) {
                        check(Shell.cmd("rm -f ${ifwPath(pkg)}").exec().isSuccess) { "ifw remove failed" }
                    } else {
                        // 批X2（审查 R06）：同目录原子替换 + 结果检查
                        val tmp = "${context.cacheDir.absolutePath}/ifw_$pkg.xml"
                        java.io.File(tmp).writeText(xml)
                        check(
                            Shell.cmd("cp $tmp ${ifwPath(pkg)}.tmp && chmod 644 ${ifwPath(pkg)}.tmp && mv ${ifwPath(pkg)}.tmp ${ifwPath(pkg)}").exec().isSuccess
                        ) { "ifw rewrite failed" }
                        java.io.File(tmp).delete()
                    }
                } else {
                    check(Shell.cmd("rm -f ${ifwPath(pkg)}").exec().isSuccess) { "ifw remove failed" }
                }
                Unit
            }
        }

    suspend fun hasIfw(pkg: String): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("test -f ${ifwPath(pkg)}").exec().isSuccess
    }

    // ── pm disable 辅引擎（app 可运行时自恢复；跨更新保留）────────
    suspend fun applyPm(pkg: String, componentNames: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (componentNames.isNotEmpty()) requireValidTarget(pkg, componentNames)
                require(!isBlockedTarget(context, pkg)) { "system app blocked by whitelist: $pkg" }
                if (componentNames.isEmpty()) return@runCatching 0
                val cmds = componentNames.joinToString(" && ") { "pm disable $pkg/$it" }
                val r = Shell.cmd(cmds).exec()
                check(r.isSuccess) { "pm disable failed: ${r.err}" }
                componentNames.size
            }
        }

    suspend fun enablePm(pkg: String, componentNames: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (componentNames.isNotEmpty()) requireValidTarget(pkg, componentNames)
                if (componentNames.isEmpty()) return@runCatching 0
                val cmds = componentNames.joinToString(" && ") { "pm enable $pkg/$it" }
                val r = Shell.cmd(cmds).exec()
                check(r.isSuccess) { "pm enable failed: ${r.err}" }
                componentNames.size
            }
        }

    // ── 批FF-B1①（七审 AA7 / 方案B S1）：PM 逐项执行——快路径块级判定，
    //    失败块内逐条定位；部分失败不再被整体退出码吞掉 ────────
    suspend fun applyPmPerItem(pkg: String, componentNames: List<String>): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (componentNames.isNotEmpty()) requireValidTarget(pkg, componentNames)
                require(!isBlockedTarget(context, pkg)) { "system app blocked by whitelist: $pkg" }
                OperationCenter.chunkedPerItem(componentNames) { block ->
                    val cmds = block.joinToString(" && ") { "pm disable $pkg/$it" }
                    Shell.cmd(cmds).exec().isSuccess
                }
            }.getOrDefault(emptyList())
        }

    suspend fun enablePmPerItem(pkg: String, componentNames: List<String>): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (componentNames.isNotEmpty()) requireValidTarget(pkg, componentNames)
                if (componentNames.isEmpty()) return@runCatching emptyList()
                OperationCenter.chunkedPerItem(componentNames) { block ->
                    val cmds = block.joinToString(" && ") { "pm enable $pkg/$it" }
                    Shell.cmd(cmds).exec().isSuccess
                }
            }.getOrDefault(emptyList())
        }

    suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 主动建立 root shell——首次调用会触发 Magisk su 授权请求
        Shell.getShell()
        Shell.isAppGrantedRoot() == true
    }
}
