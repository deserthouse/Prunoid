package io.github.deserthouse.sdkpruner.core.engine

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.deserthouse.sdkpruner.core.rules.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// IFW XML 生成（纯函数，便于单测）——格式经 Blocker MIT 源码对照验证：
// <activity block="true" log="true"><component-filter name="pkg/cls"/></activity>
// provider 不受 IFW 支持，跳过
object IfwXmlBuilder {
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
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<rules>\n" +
            groups.joinToString("\n") + "\n</rules>"
    }
}

// M1-R4 禁用引擎：IFW 主 + pm disable 辅，含安全层（备份/恢复/系统白名单）
class DisableEngine(
    private val context: Context,
    private val rules: RuleRepository
) {
    companion object {
        init { Shell.enableVerboseLogging = false; Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10)) }

        // 系统/框架包白名单（M1 硬拦截；数据底座见 blocker-general-rules components/）
        val SYSTEM_PREFIXES = listOf(
            "android", "com.android.", "com.google.android.", "androidx.",
            "com.android.internal.", "miui", "com.miui.", "com.samsung.",
            "com.huawei.", "com.hihonor.", "com.oplus.", "com.coloros.",
            "com.vivo.", "com.xiaomi.", "org.chromium."
        )

        fun isForbidden(packageName: String): Boolean =
            packageName in SYSTEM_PREFIXES || SYSTEM_PREFIXES.any { packageName.startsWith(it) }

        private fun ifwPath(pkg: String) = "/data/system/ifw/$pkg.xml"
        private const val IFW_DIR = "/data/system/ifw"
        private const val PKG_RESTRICTIONS = "/data/system/users/0/package-restrictions.xml"
    }

    // ── 安全层：操作前全量备份（IFW 规则目录 + pm 组件限制状态，单个 tar 归档） ──
    suspend fun backup(keep: Int = 10): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = "${context.filesDir.absolutePath}/backups"
            Shell.cmd("mkdir -p $dir").exec()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = "$dir/backup_$ts.tar.gz"
            // 单次归档两个路径（-C / 用相对路径），失败则整体报错——不能静默丢一半
            val r = Shell.cmd(
                "tar -czf $out -C / $IFW_DIR $PKG_RESTRICTIONS"
            ).exec()
            val has = Shell.cmd("test -s $out").exec().isSuccess
            check(has) { "backup not created (tar rc=${r.code}, root ok?): ${r.err}" }
            pruneBackups(dir, keep)
            out
        }
    }

    /** 备份保留策略：按文件名时间戳降序，只留最近 keep 份（用户可在设置中调） */
    private fun pruneBackups(dir: String, keep: Int) {
        val files = Shell.cmd("ls -1 $dir/backup_*.tar.gz 2>/dev/null").exec().out.toList().sortedDescending()
        files.drop(keep.coerceIn(3, 30)).forEach { Shell.cmd("rm -f $it").exec() }
    }

    // ── 安全层：恢复备份（tar 解包回原路径 + 归属/上下文修复） ─────
    suspend fun restoreBackup(backupPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val r = Shell.cmd(
                "tar -xzf $backupPath -C / && " +
                "chown -R system:system $IFW_DIR && chmod 644 $IFW_DIR/*.xml; " +
                "chown system:system $PKG_RESTRICTIONS && restorecon -R $IFW_DIR $PKG_RESTRICTIONS"
            ).exec()
            check(r.isSuccess) { "restore failed: ${r.err}" }
            Unit
        }
    }

    fun listBackups(): List<String> =
        Shell.cmd("ls ${context.filesDir.absolutePath}/backups/backup_*.tar.gz 2>/dev/null")
            .exec().out.toList().sortedDescending()

    // ── 安全层：一键清除全部 IFW 规则（紧急恢复） ─────────────────
    suspend fun clearAllIfw(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val res = Shell.cmd("ls $IFW_DIR/*.xml 2>/dev/null").exec()
            val files = res.out.toList()
            if (files.isNotEmpty()) Shell.cmd("rm -f ${files.joinToString(" ")}").exec()
            files.size
        }
    }

    // ── IFW 主引擎 ────────────────────────────────────────────────
    suspend fun applyIfw(pkg: String, byType: Map<String, List<String>>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(!isForbidden(pkg)) { "system app blocked by whitelist: $pkg" }
                val named = byType.mapValues { (_, comps) -> comps.map { "$pkg/$it" } }
                val xml = IfwXmlBuilder.build(named)
                    ?: return@runCatching 0
                val tmp = "${context.cacheDir.absolutePath}/ifw_$pkg.xml"
                java.io.File(tmp).writeText(xml)
                val r = Shell.cmd(
                    "mkdir -p $IFW_DIR && cp $tmp ${ifwPath(pkg)} && " +
                    "chmod 644 ${ifwPath(pkg)} && restorecon ${ifwPath(pkg)} 2>/dev/null; rm -f $tmp"
                ).exec()
                check(r.isSuccess) { "ifw write failed: ${r.err}" }
                byType.values.sumOf { it.size }
            }
        }

    suspend fun removeIfw(pkg: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!isForbidden(pkg)) { "system app blocked by whitelist: $pkg" }
            Shell.cmd("rm -f ${ifwPath(pkg)}").exec()
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
                require(!isForbidden(pkg)) { "system app blocked by whitelist: $pkg" }
                if (componentNames.isEmpty()) return@runCatching 0
                val cmds = componentNames.joinToString("; ") { "pm disable $pkg/$it" }
                val r = Shell.cmd(cmds).exec()
                check(r.isSuccess) { "pm disable failed: ${r.err}" }
                componentNames.size
            }
        }

    suspend fun enablePm(pkg: String, componentNames: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (componentNames.isEmpty()) return@runCatching 0
                val cmds = componentNames.joinToString("; ") { "pm enable $pkg/$it" }
                Shell.cmd(cmds).exec()
                componentNames.size
            }
        }

    suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 主动建立 root shell——首次调用会触发 Magisk su 授权请求
        Shell.getShell()
        Shell.isAppGrantedRoot() == true
    }
}
