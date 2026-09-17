package io.github.deserthouse.sdkpruner.core.engine

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.deserthouse.sdkpruner.core.rules.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// M1-R4 禁用引擎：IFW 主 + pm disable 辅，含安全层（备份/恢复/系统白名单）
// 参考实现：Blocker core/ifw-api（MIT）的 CRUD 语义：按包分文件、空规则删文件
class DisableEngine(
    private val context: Context,
    private val rules: RuleRepository
) {
    companion object {
        init { Shell.enableVerboseLogging = false; Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10)) }

        // 系统/框架包白名单（M1 硬拦截，绝不允许写入任何规则；数据底座见 blocker-general-rules components/）
        val SYSTEM_PREFIXES = listOf(
            "android", "com.android.", "com.google.android.", "androidx.",
            "com.android.internal.", "miui", "com.miui.", "com.samsung.",
            "com.huawei.", "com.hihonor.", "com.oplus.", "com.coloros.",
            "com.vivo.", "com.xiaomi.", "com.tencent.android.tpush.", "org.chromium."
        )

        fun isForbidden(packageName: String): Boolean =
            packageName in SYSTEM_PREFIXES || SYSTEM_PREFIXES.any { packageName.startsWith(it) }

        private fun ifwPath(pkg: String) = "/data/system/ifw/$pkg.xml"
    }

    // ── 安全层：操作前全量备份 ─────────────────────────────────────
    suspend fun backup(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = "${context.filesDir.absolutePath}/backups"
            Shell.cmd("mkdir -p $dir").exec()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = "$dir/backup_$ts.tar.gz"
            // IFW 规则 + pm 组件限制状态一并打包（root 可读）
            Shell.cmd(
                "tar -czf $out -C / data/system/ifw 2>/dev/null; " +
                "cp /data/system/users/0/package-restrictions.xml $dir/_pr_$ts.xml 2>/dev/null; " +
                "tar -czf $out -C $dir _pr_$ts.xml 2>/dev/null || true; rm -f $dir/_pr_$ts.xml"
            ).exec()
            if (Shell.cmd("test -f $out").exec().isSuccess) out
            else throw IllegalStateException("backup file not created (root unavailable?)")
        }
    }

    // ── 安全层：一键清除全部 IFW 规则（紧急恢复）──────────────────
    suspend fun clearAllIfw(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val res = Shell.cmd("ls /data/system/ifw/*.xml 2>/dev/null").exec()
            val files = res.out.toList()
            if (files.isNotEmpty()) Shell.cmd("rm -f ${files.joinToString(" ")}").exec()
            files.size
        }
    }

    suspend fun restoreBackup(backupPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Shell.cmd("tar -xzf $backupPath -C /").exec()
            Unit
        }
    }

    fun listBackups(): List<String> =
        Shell.cmd("ls ${context.filesDir.absolutePath}/backups/backup_*.tar.gz 2>/dev/null")
            .exec().out.toList()

    // ── IFW 主引擎 ────────────────────────────────────────────────
    // IFW 真实格式（经 Blocker 对照验证）：<activity block="true" log="true"><component-filter name="pkg/cls"/></activity>
    // 注意不是 <activity-blocks> 容器语法；provider 不受 IFW 支持（Blocker 同样跳过）
    suspend fun applyIfw(pkg: String, byType: Map<String, List<String>>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(!isForbidden(pkg)) { "system app blocked by whitelist: $pkg" }
                val groups = byType.mapNotNull { (type, comps) ->
                    if (comps.isEmpty() || type == "provider") return@mapNotNull null
                    val tag = when (type) {
                        "service" -> "service"
                        "receiver" -> "receiver"
                        else -> "activity"
                    }
                    "  <$tag block=\"true\" log=\"true\">\n" +
                        comps.joinToString("\n") { "    <component-filter name=\"$pkg/$it\" />" } +
                        "\n  </$tag>"
                }
                if (groups.isEmpty()) return@runCatching 0
                val xml = "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<rules>\n" +
                    groups.joinToString("\n") + "\n</rules>"
                val tmp = "${context.cacheDir.absolutePath}/ifw_$pkg.xml"
                java.io.File(tmp).writeText(xml)
                val r = Shell.cmd(
                    "mkdir -p /data/system/ifw && cp $tmp ${ifwPath(pkg)} && " +
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
