package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.PackageEventGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批Z5（第五轮审查 R5-16）：包事件去重闸——窗口期内同一包的重复事件只放行一次。 */
class PackageEventGateTest {

    @Test
    fun `duplicate event inside the window is dropped and the next one after it passes`() {
        val g = PackageEventGate(windowMs = 1500)
        assertTrue(g.admit("pkg.a", 1000))
        assertFalse(g.admit("pkg.a", 1200))
        assertFalse(g.admit("pkg.a", 2499))
        assertTrue(g.admit("pkg.a", 2500))
    }

    @Test
    fun `packages are independent`() {
        val g = PackageEventGate(windowMs = 1500)
        assertTrue(g.admit("pkg.a", 1000))
        assertTrue(g.admit("pkg.b", 1000))
        assertFalse(g.admit("pkg.a", 1100))
    }

    @Test
    fun `the table does not grow without bound`() {
        val g = PackageEventGate(windowMs = 100)
        var t = 0L
        repeat(2000) { g.admit("pkg$it", t); t += 1000 }
        // 过期条目已被清掉（>256 条时触发清理）
        assertTrue(g.admit("pkg0", t))
    }
}
