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
        // 引擎里每一条进 shell 的 pm enable/disable 目标都必须经 pmTarget 加单引号；IFW 规则里的组件名写进 XML 文件，不经 shell 插值
        val text = engineText()
        val quoted = Regex(""""pm (?:enable|disable) +[$][{]pmTarget[(]""")
        val bare = Regex("""pm (?:enable|disable) +[^'"]*[$]pkg""")
        assertEquals(4, quoted.findAll(text).count())
        assertEquals("unquoted pm target in DisableEngine: " + bare.findAll(text).map { it.value }.toList(),
            0, bare.findAll(text).count())
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
