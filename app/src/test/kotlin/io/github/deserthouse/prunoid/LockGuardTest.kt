package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.OpCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 第五轮审查 P2：全局写锁的串行性（运行时）与"取锁路径不得再取锁"（源码静态守卫）。
 *
 *  OpCoordinator 的 Mutex 不可重入：持锁路径里再调一个会取锁的入口 = 永久挂死（无超时、无报错）。
 *  会取锁的入口只有三类：OpCoordinator.write、VM.withBusy(locked = true)、
 *  DisableEngine.clearIfwTransaction / AutoReapply.process（内部各自取锁）。
 *  本守卫按函数文本窗口检查它们不共现——是护栏不是证明（窗口按花括号配对，近似）。 */
class LockGuardTest {

    // ── 运行时：串行 ──────────────────────────────────────────────
    @Test
    fun `concurrent writers are serialized with no lost updates`() = runBlocking {
        var value = 0
        val inside = AtomicInteger()
        var maxInside = 0
        withContext(Dispatchers.Default) {
            (1..40).map {
                async {
                    OpCoordinator.write {
                        val now = inside.incrementAndGet()
                        synchronized(this@LockGuardTest) { if (now > maxInside) maxInside = now }
                        delay(5)
                        value = value + 1 // 故意非原子：只有串行才不丢更新
                        inside.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(40, value)
        assertEquals(1, maxInside)
    }

    /** 现状刻画：嵌套取锁不会报错，只会等到超时——正是静态守卫存在的理由。 */
    @Test
    fun `nested write lock does not reenter`() = runBlocking {
        try {
            withTimeout(1000) { OpCoordinator.write { OpCoordinator.write { 1 } } }
            fail("nested lock unexpectedly reentered")
        } catch (e: TimeoutCancellationException) {
            // 预期：第二次 write 永远拿不到锁
        }
    }

    // ── 源码静态守卫 ──────────────────────────────────────────────
    private val lockToken = "OpCoordinator.write"
    /** withBusy(…, locked = true)：第一参数可含一层括号（如 if (on) "a" else "b"） */
    private val busyLockedRe = Regex("""withBusy\((?:[^()]|\([^()]*\))*locked\s*=\s*true""")
    private val lockTakers = listOf("clearIfwTransaction(", "AutoReapply.process(")

    private fun sourceRoot(): File =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }

    /** 从 fun 声明行起、按花括号配对到深度归零的文本窗口（表达式体单行=窗口仅一行）。 */
    fun functionWindows(lines: List<String>): List<Pair<String, List<String>>> {
        val funRe = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:override|private|internal|public|protected|suspend|inline|operator|infix)\s+)*fun\s+([\w<>.,\s]+?)\s*\(""")
        val out = mutableListOf<Pair<String, List<String>>>()
        var i = 0
        while (i < lines.size) {
            val m = funRe.find(lines[i])
            if (m == null) { i++; continue }
            var depth = 0
            var j = i
            while (j < lines.size) {
                depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                if (depth <= 0) break // 单行表达式体（无花括号）或块体结束
                j++
            }
            out += m.groupValues[1].trim() to lines.subList(i, minOf(j + 1, lines.size))
            i = j + 1
        }
        return out
    }

    private fun holdsLock(body: List<String>): Boolean {
        // 去掉声明头（fun 名(…)）：自己的名字不是"调用"；表达式体的 `= OpCoordinator.write {` 保留
        val head = body.first()
        val cut = listOfNotNull(head.indexOf('{').takeIf { it >= 0 }, head.indexOf('=').takeIf { it >= 0 }).maxOrNull()
        val text = (listOf(if (cut != null) head.substring(cut) else "") + body.drop(1)).joinToString("\n")
        return text.contains(lockToken) || busyLockedRe.containsMatchIn(text)
    }

    private fun violations(lines: List<String>): List<String> {
        val bad = mutableListOf<String>()
        for ((name, body) in functionWindows(lines)) {
            if (!holdsLock(body)) continue
            val head = body.first()
            val cut = listOfNotNull(head.indexOf('{').takeIf { it >= 0 }, head.indexOf('=').takeIf { it >= 0 }).maxOrNull()
            val text = (listOf(if (cut != null) head.substring(cut) else "") + body.drop(1)).joinToString("\n")
            val holdsLock = busyLockedRe.containsMatchIn(text) ||
                text.contains(lockToken)
            if (!holdsLock) continue
            val hits = lockTakers.filter { text.contains(it) } +
                (if (text.split(lockToken).size - 1 >= 2) listOf("$lockToken (twice)") else emptyList()) +
                (if (text.contains(lockToken) && busyLockedRe.containsMatchIn(text))
                    listOf("$lockToken + withBusy(locked=true)") else emptyList())
            if (hits.isNotEmpty()) bad += "$name: ${hits.joinToString(", ")}"
        }
        return bad
    }

    /** 仪器自检：已知嵌套的样例必须被抓出来（否则下面的源码检查是恒真的假阴性）。 */
    @Test
    fun `detector flags a known nested lock sample`() {
        val sample = """
            class X {
                fun outer() {
                    OpCoordinator.write {
                        disableSdkEverywhere()
                    }
                }
                fun disableSdkEverywhere() {
                    val msg = withBusy("everywhere", locked = true) {
                        DisableEngine.clearIfwTransaction(ctx)
                    }
                }
            }
        """.trimIndent().lines()
        val v = violations(sample)
        assertEquals(listOf<String>(), v.filter { it.startsWith("outer") }) // outer 里没有会取锁的被调方
        assertTrue(v.toString(), v.any { it.startsWith("disableSdkEverywhere") && it.contains("clearIfwTransaction") })
    }

    @Test
    fun `no production function takes the write lock and then calls another lock taker`() {
        val files = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 10)
        val bad = files.flatMap { f -> violations(f.readLines()).map { "${f.name}: $it" } }
        assertEquals("lock nesting candidates: $bad", emptyList<String>(), bad)
    }

    /** 阳性对照：守卫必须真的看得见生产代码里的持锁函数，否则上一条是恒真的假阴性。 */
    @Test
    fun `detector sees the known lock holders in the view model`() {
        val f = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt")
        val holders = functionWindows(f.readLines()).filter { holdsLock(it.second) }.map { it.first }
        for (expected in listOf("disableSdkEverywhere", "setSdkForApp", "applyRules", "restoreApp", "restoreBackup", "disableUnmatched")) {
            assertTrue("$expected not seen as a lock holder; seen=$holders", expected in holders)
        }
    }

    @Test
    fun `engine module holds the lock only in clearIfwTransaction`() {
        val f = sourceRoot().resolve("io/github/deserthouse/prunoid/core/engine/DisableEngine.kt")
        val text = f.readText()
        assertEquals(1, text.split(lockToken).size - 1)
        val holder = functionWindows(f.readLines()).filter { (_, b) -> b.joinToString("\n").contains(lockToken) }
        assertEquals(listOf("clearIfwTransaction"), holder.map { it.first })
    }
}
