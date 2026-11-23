package io.github.deserthouse.prunoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 批Y7（第五轮审查 R5-19）：源码守卫——AppViewModel 里每一处账本写入都必须包在 ledgerWrite { } 里。
 *  背景：账本写失败曾直接炸出协程（FATAL EXCEPTION），而此时规则已写入系统=孤儿规则+崩溃。
 *  守卫是护栏不是证明（按文本窗口判断），配合阳性对照防恒真。 */
class LedgerWriteGuardTest {

    private val call = Regex("""applied\.(recordMerge|recordReplace|replaceBuckets|remove|removeEngine|clearAll)\(""")

    private fun sourceRoot(): File =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }

    /** 返回未被 ledgerWrite { 包住的账本写入调用行号（1 起） */
    fun unguarded(lines: List<String>): List<Int> =
        lines.indices.filter { call.containsMatchIn(lines[it]) }
            .filterNot { i ->
                "ledgerWrite {" in lines[i] || (maxOf(0, i - 4) until i).any { "ledgerWrite {" in lines[it] }
            }
            .map { it + 1 }

    @Test
    fun `detector flags a bare ledger write and accepts a wrapped one`() {
        val sample = listOf(
            "fun a() {",
            "    applied.recordMerge(pkg, \"IFW\", comps, mapOf())",
            "}",
            "fun b() {",
            "    if (ledgerWrite {",
            "        applied.recordMerge(pkg, \"IFW\", comps, mapOf())",
            "    } != null) return",
            "}",
        )
        assertEquals(listOf(2), unguarded(sample))
    }

    @Test
    fun `every ledger write in the view model is wrapped`() {
        val lines = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt").readLines()
        val sites = lines.count { call.containsMatchIn(it) }
        assertTrue("expected to see the known ledger write sites, saw $sites", sites >= 9)
        assertEquals("unwrapped ledger writes at lines: ${unguarded(lines)}", emptyList<Int>(), unguarded(lines))
    }
}
