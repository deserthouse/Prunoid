package io.github.deserthouse.prunoid.core.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 批Z1（第五轮审查 R5-05）：pm 组件启停状态的时点侧表（纯函数部分）。
 *
 *  备份归档里一直打包着系统的 package-restrictions.xml，但该文件在 Android 16 上由系统以
 *  fs-verity 保护、root 也写不进——"替换文件"式还原不可能生效（重启后同样不生效）。
 *  改为：备份时用 PackageManager 读出各第三方应用组件的启停状态，另存为与归档同名的
 *  `backup_<ts>.pm.json`；还原时比对现状，对差异组件逐个执行 pm enable/disable/default-state。
 *  本对象只负责编码与差异计算（可 JVM 测试），采集与执行在 [DisableEngine]。 */
object PmStateSnapshot {

    const val SUFFIX = ".pm.json"
    const val DEFAULT = "DEFAULT"

    /** states：包名 → （组件全类名 → 状态名）。只记录快照时非 DEFAULT 的组件。 */
    @Serializable
    data class Sidecar(val v: Int = 1, val takenAt: Long = 0, val states: Map<String, Map<String, String>> = emptyMap())

    enum class Cmd(val arg: String) {
        ENABLE("enable"), DISABLE("disable"), DISABLE_USER("disable-user"),
        DISABLE_UNTIL_USED("disable-until-used"), DEFAULT("default-state"),
    }

    data class Action(val pkg: String, val comp: String, val cmd: Cmd)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun sidecarPath(tarPath: String): String = tarPath.removeSuffix(".tar.gz") + SUFFIX

    fun encode(s: Sidecar): String = json.encodeToString(Sidecar.serializer(), s)

    fun decode(text: String): Sidecar = json.decodeFromString(Sidecar.serializer(), text)

    fun cmdFor(state: String): Cmd = when (state) {
        "ENABLED" -> Cmd.ENABLE
        "DISABLED" -> Cmd.DISABLE
        "DISABLED_USER" -> Cmd.DISABLE_USER
        "DISABLED_UNTIL_USED" -> Cmd.DISABLE_UNTIL_USED
        else -> Cmd.DEFAULT
    }

    fun command(a: Action): String = "pm ${a.cmd.arg} ${a.pkg}/${a.comp}"

    /** 让现状回到侧表记录的状态。
     *  - 只处理侧表里出现过的应用（快照之后才安装的应用不动）；
     *  - 应用已被卸载、或组件已不存在（应用升级后）→ 跳过，无从设置；
     *  - 侧表没有记录、现状却非 DEFAULT 的组件（快照之后才被改动的）→ 回到 DEFAULT。 */
    fun diff(sidecar: Sidecar, current: Map<String, Map<String, String>>): List<Action> {
        val out = mutableListOf<Action>()
        for ((pkg, want) in sidecar.states) {
            val have = current[pkg] ?: continue
            for ((comp, w) in want) {
                val h = have[comp] ?: continue
                if (h != w) out += Action(pkg, comp, cmdFor(w))
            }
            for ((comp, h) in have) {
                if (comp !in want && h != DEFAULT) out += Action(pkg, comp, Cmd.DEFAULT)
            }
        }
        return out
    }

    // ── 0.32.1（第六轮审查 R6-01/R6-04）：回放计划与结果统计 ──────────────

    /** 回放计划。[read] 读某应用组件的现状：已卸载返回 null（无从设置，跳过）；
     *  其他失败抛异常——计入 [readFailed]，不当成已卸载。[allow] 是命令进 shell 前的白名单。 */
    data class ReplayPlan(val actions: List<Action>, val readFailed: Int)

    fun planReplay(
        sidecar: Sidecar,
        allow: (Action) -> Boolean,
        read: (String) -> Map<String, String>?,
    ): ReplayPlan {
        val now = LinkedHashMap<String, Map<String, String>>()
        var readFailed = 0
        for (pkg in sidecar.states.keys) {
            try {
                read(pkg)?.let { now[pkg] = it }
            } catch (e: Exception) {
                readFailed++
            }
        }
        return ReplayPlan(diff(sidecar, now).filter(allow), readFailed)
    }

    /** 回放结果：failed = 命令失败数 + 读取现状失败的应用数；一项都没失败才算还原 */
    data class ReplayOutcome(val applied: Int, val failed: Int) {
        val ok: Boolean get() = failed == 0
    }

    fun replayOutcome(succeeded: Int, attempted: Int, readFailed: Int): ReplayOutcome =
        ReplayOutcome(succeeded, (attempted - succeeded).coerceAtLeast(0) + readFailed)
}
