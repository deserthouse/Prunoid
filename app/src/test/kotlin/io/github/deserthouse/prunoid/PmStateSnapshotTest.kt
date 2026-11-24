package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Action
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Cmd
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.Sidecar
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.command
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.cmdFor
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.decode
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.diff
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.encode
import io.github.deserthouse.prunoid.core.engine.PmStateSnapshot.sidecarPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批Z1（第五轮审查 R5-05）：pm 状态侧表的差异计算——还原=让现状回到快照时的状态。 */
class PmStateSnapshotTest {

    private val sidecar = Sidecar(
        takenAt = 1,
        states = mapOf(
            "pkg.a" to mapOf("p/X" to "DISABLED", "p/Y" to "ENABLED"),
            "pkg.gone" to mapOf("p/Z" to "DISABLED"),
        ),
    )

    @Test
    fun `diff puts back snapshot states and reverts later changes`() {
        val current = mapOf(
            // X 在快照后被改回默认；W 在快照后才被改动
            "pkg.a" to mapOf("p/X" to "DEFAULT", "p/Y" to "ENABLED", "p/W" to "DISABLED_USER"),
        )
        assertEquals(
            listOf(Action("pkg.a", "p/X", Cmd.DISABLE), Action("pkg.a", "p/W", Cmd.DEFAULT)),
            diff(sidecar, current),
        )
    }

    @Test
    fun `no actions when the present matches the snapshot`() {
        val current = mapOf(
            "pkg.a" to mapOf("p/X" to "DISABLED", "p/Y" to "ENABLED", "p/W" to "DEFAULT"),
            "pkg.installed.later" to mapOf("p/N" to "DISABLED"),   // 快照后安装的应用不动
        )
        assertEquals(emptyList<Action>(), diff(sidecar, current))
    }

    @Test
    fun `uninstalled packages and vanished components are skipped`() {
        assertEquals(emptyList<Action>(), diff(sidecar, emptyMap()))
        assertEquals(emptyList<Action>(), diff(sidecar, mapOf("pkg.a" to emptyMap())))
    }

    @Test
    fun `state names map to pm commands`() {
        assertEquals(Cmd.ENABLE, cmdFor("ENABLED"))
        assertEquals(Cmd.DISABLE, cmdFor("DISABLED"))
        assertEquals(Cmd.DISABLE_USER, cmdFor("DISABLED_USER"))
        assertEquals(Cmd.DISABLE_UNTIL_USED, cmdFor("DISABLED_UNTIL_USED"))
        assertEquals(Cmd.DEFAULT, cmdFor("something else"))
        assertEquals("pm disable-user pkg.a/p/X", command(Action("pkg.a", "p/X", Cmd.DISABLE_USER)))
        assertEquals("pm default-state pkg.a/p/X", command(Action("pkg.a", "p/X", Cmd.DEFAULT)))
    }

    @Test
    fun `encode decode round trip and sidecar path sit next to the archive`() {
        assertEquals("/d/backups/backup_20260101-010203.pm.json", sidecarPath("/d/backups/backup_20260101-010203.tar.gz"))
        assertEquals(sidecar, decode(encode(sidecar)))
    }
}
