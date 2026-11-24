package io.github.deserthouse.prunoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.32.1：操作结果日志钩子的契约守卫——发版冒烟门禁（tests/smoke_gate.py）按
 *  "op-result: <op> | <msg>" 断言界面提示的实际文本。钩子被改名/删除时这里先红。 */
class OpResultLogGuardTest {

    private val ops = listOf(
        "apply", "sdk-off", "sdk-everywhere", "unmatched", "source",
        "restore-app", "restore-backup", "clear-ifw",
    )

    private fun vm(): String =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
            .first { it.isDirectory }
            .resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt").readText()

    @Test
    fun `every user-facing op reports its final message`() {
        val text = vm()
        assertTrue("op-result format changed", "\"op-result: \$op | \$msg\"" in text)
        for (op in ops) {
            assertTrue("op \"$op\" no longer reports", "report(\"$op\"" in text)
        }
    }
}
