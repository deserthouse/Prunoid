package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Cmd
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Action
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.ReplayPlan
import org.junit.Assert.assertEquals
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Sidecar
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.planReplay
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.replayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.32.1（第六轮审查 R6-01/R6-04）：pm 回放的读取失败语义与兜底——源码守卫 + 纯函数探针。 */
class PmReplayGuardTest {

    private fun sourceRoot(): File =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }

    private fun engineText(): String =
        sourceRoot().resolve("io/github/deserthouse/prunoid/core/engine/DisableEngine.kt").readText()

    /** 回放段（sidecar 判定之后、RestoreReport 之前）是否带兜底 */
    fun guarded(block: String): Boolean =
        "runCatching {" in block && ".onFailure {" in block && "maxOf(pmFailed, 1)" in block

    @Test
    fun `detector accepts a wrapped replay block and rejects a bare one`() {
        val good = "if (sidecar != null) { runCatching { x }.onFailure { pmFailed = maxOf(pmFailed, 1) } }"
        val bare = "if (sidecar != null) { x }"
        assertTrue(guarded(good))
        assertFalse(guarded(bare))
    }

    @Test
    fun `pm replay is wrapped so a crash degrades to pm not restored`() {
        val text = engineText()
        val a = text.indexOf("if (sidecar != null) {")
        val b = text.indexOf("RestoreReport(ifwOk = true")
        assertTrue("replay block anchors not found", a in 0 until b)
        val block = text.substring(a, b)
        assertTrue("replay block lacks crash guard:\n$block", guarded(block))
        assertTrue("read failures must feed the failed count", "plan.readFailed" in block)
    }

    @Test
    fun `reading current state only swallows package-missing`() {
        val text = engineText()
        val a = text.indexOf("private fun readPmStatesOf(")
        val b = text.indexOf("fun collectPmStates()")
        assertTrue("readPmStatesOf anchors not found", a in 0 until b)
        val f = text.substring(a, b)
        assertTrue("catch (e: PackageManager.NameNotFoundException)" in f)
        assertFalse("readPmStatesOf must not fold all failures into null:\n$f", "getOrNull" in f || "runCatching" in f)
    }

    @Test
    fun `pm ledger is only cleared when the replay reported ok`() {
        val vm = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt").readText()
        assertTrue(
            "pm ledger removal must be gated on report.pmOk",
            Regex("""report\.pmOk && ledgerWrite \{ applied\.removeEngine\(Engine\.PM\.name\) \} != null""").containsMatchIn(vm),
        )
    }

    @Test
    fun `pm commands quote the component target so dollar signs survive the shell`() {
        // activity-alias / 内部类名：$Inner 不加引号会被 shell 展开成空，命令落到外层类上
        val a = Action("cn.kaiheila", "com.x.StubActivity\$Activity_Portrait", Cmd.DISABLE)
        assertEquals("pm disable 'cn.kaiheila/com.x.StubActivity\$Activity_Portrait'", PmStateSnapshot.command(a))
        assertEquals("pm default-state 'cn.kaiheila/com.x.StubActivity\$Activity_Portrait'", PmStateSnapshot.command(a.copy(cmd = Cmd.DEFAULT)))
        // 引擎里每一条进 shell 的 pm 命令目标都必须带单引号：停用走 pmTarget，还原/回放走 PmStateSnapshot.command
        // （0.33.1 起恢复按状态分派，原"逐项启用"并入 restorePmPerItem）；IFW 规则里的组件名写进 XML 文件，不经 shell 插值
        val text = engineText()
        val disable = Regex(""""pm disable +[$][{]pmTarget[(]""")
        val byState = Regex("""PmStateSnapshot\.command\(""")
        val bare = Regex("""pm (?:enable|disable|default-state|disable-user) +[^'"\n]*[$]pkg""")
        assertEquals(2, disable.findAll(text).count())
        assertEquals("every pm state command must be built by PmStateSnapshot.command", 2, byState.findAll(text).count())
        assertEquals("unquoted pm target in DisableEngine: " + bare.findAll(text).map { it.value }.toList(),
            0, bare.findAll(text).count())
        assertFalse("bare pm enable must not come back", "pm enable \${" in text)
    }

    // ── 0.33.1：按状态还原的入口守卫 ──────────────────────────────

    @Test
    fun `restore entry validates names and only accepts enable or default-state`() {
        val text = engineText()
        val a = text.indexOf("suspend fun restorePmPerItem(")
        val b = text.indexOf("private val RESTORE_CMDS")
        assertTrue("restorePmPerItem anchors not found", a in 0 until b)
        val f = text.substring(a, b)
        // 账本数据进 shell 命令前必须过白名单（批W5/M09 的公共入口校验不能在重构里丢）
        assertTrue("restorePmPerItem must validate component names", "requireValidTarget(" in f)
        assertTrue("restorePmPerItem must reject other packages or commands", "it.pkg == pkg && it.cmd in RESTORE_CMDS" in f)
        assertTrue(
            "RESTORE_CMDS must stay enable + default-state",
            "private val RESTORE_CMDS = setOf(PmStateSnapshot.Cmd.ENABLE, PmStateSnapshot.Cmd.DEFAULT)" in text,
        )
        // "逐项启用"已被取代，不得残留（否则恢复路径会绕过写入前状态）
        assertFalse(text.contains("fun enablePmPerItem("))
        assertFalse(text.contains("fun enablePm("))
    }

    @Test
    fun `every restore path in the view model goes through state-based restore`() {
        val vm = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt").readText()
        // 四处：单应用账本写失败撤销、关闭 SDK、批量撤销、整应用恢复
        assertEquals(4, Regex("""engine\.restorePmPerItem\(""").findAll(vm).count())
        assertEquals("no enable-only restore may remain", 0, Regex("""enablePmPerItem\(""").findAll(vm).count())
        assertEquals(2, Regex("""RestoreAccounting\.restoreActions\(""").findAll(vm).count())
        // 批量路径：账本写失败的包必须走撤销，而不是带着未记账的变更继续
        assertEquals(1, Regex("""BulkPlanExecutor\.undoUnrecorded\(""").findAll(vm).count())
    }

    // ── 纯函数探针 ──────────────────────────────────────────────

    @Test
    fun `unreadable package is a replay failure, not an uninstall`() {
        val sc = Sidecar(
            takenAt = 1,
            states = mapOf("pkg.a" to mapOf("p/X" to "DISABLED"), "pkg.b" to mapOf("p/Y" to "DISABLED")),
        )
        val plan: ReplayPlan = planReplay(sc, allow = { true }) { pkg ->
            if (pkg == "pkg.a") throw IllegalStateException("transient PackageManager failure")
            mapOf("p/Y" to PmStateSnapshot.DEFAULT)
        }
        // pkg.a 没读出现状：不产生命令，但记为失败
        assertEquals(1, plan.readFailed)
        assertEquals(listOf(Action("pkg.b", "p/Y", Cmd.DISABLE)), plan.actions)
        val outcome = replayOutcome(succeeded = 1, attempted = 1, readFailed = plan.readFailed)
        assertEquals(1, outcome.failed)
        assertFalse("must not report everything restored", outcome.ok)
    }

    @Test
    fun `uninstalled package is skipped without counting as failure`() {
        val sc = Sidecar(takenAt = 1, states = mapOf("pkg.a" to mapOf("p/X" to "DISABLED"), "pkg.gone" to mapOf("p/Z" to "DISABLED")))
        val plan = planReplay(sc, allow = { true }) { pkg ->
            if (pkg == "pkg.gone") null else mapOf("p/X" to "DEFAULT")
        }
        assertEquals(0, plan.readFailed)
        assertEquals(listOf(Action("pkg.a", "p/X", Cmd.DISABLE)), plan.actions)
        assertTrue(replayOutcome(1, 1, plan.readFailed).ok)
    }

    @Test
    fun `actions failing the name whitelist never reach the shell`() {
        val sc = Sidecar(takenAt = 1, states = mapOf("pkg.a" to mapOf("p/X" to "DISABLED")))
        var asked = 0
        val plan = planReplay(sc, allow = { asked++; false }) { mapOf("p/X" to "DEFAULT") }
        assertEquals(1, asked)
        assertTrue(plan.actions.isEmpty())
        assertTrue(replayOutcome(0, 0, 0).ok)
    }

    @Test
    fun `outcome tallies command failures and read failures`() {
        assertTrue(replayOutcome(succeeded = 5, attempted = 5, readFailed = 0).ok)
        assertEquals(3, replayOutcome(succeeded = 3, attempted = 5, readFailed = 1).failed)
        assertEquals(2, replayOutcome(succeeded = 0, attempted = 0, readFailed = 2).failed)
    }
}
