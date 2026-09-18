package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.rules.MultiSourceMerger
import io.github.deserthouse.prunoid.core.rules.SdkRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiSourceMergerTest {

    private fun rule(id: String, conf: String = "low") =
        SdkRule(id = id, name = id, confidence = conf)

    @Test
    fun `builtin only returns as-is`() {
        val m = MultiSourceMerger.mergeMultiSource(listOf(rule("a")), emptyList())
        assertEquals(1, m.size)
    }

    @Test
    fun `new id from source is appended`() {
        val m = MultiSourceMerger.mergeMultiSource(listOf(rule("a")), listOf(listOf(rule("b"))))
        assertEquals(2, m.size)
    }

    @Test
    fun `higher confidence wins on same id`() {
        val m = MultiSourceMerger.mergeMultiSource(
            listOf(rule("x", "medium")), listOf(listOf(rule("x", "high")))
        )
        assertEquals("high", m.first().confidence)
    }

    @Test
    fun `lower confidence does not override`() {
        val m = MultiSourceMerger.mergeMultiSource(
            listOf(rule("x", "high")), listOf(listOf(rule("x", "low")))
        )
        assertEquals("high", m.first().confidence)
    }

    @Test
    fun `tie keeps earlier source`() {
        val m = MultiSourceMerger.mergeMultiSource(
            listOf(rule("x", "medium")),
            listOf(listOf(rule("x", "medium")), listOf(rule("x", "high").let { SdkRule(id = "x", name = "x2", confidence = "medium") }))
        )
        assertEquals("x", m.first().name)  // 平手保留先入
    }

    @Test
    fun `later source higher confidence overrides earlier source`() {
        val m = MultiSourceMerger.mergeMultiSource(
            listOf(rule("x", "low")),
            listOf(listOf(rule("x", "medium")), listOf(rule("x", "high")))
        )
        assertEquals("high", m.first().confidence)
    }

    @Test
    fun `blank confidence treated as low`() {
        val m = MultiSourceMerger.mergeMultiSource(
            listOf(SdkRule(id = "x", name = "x", confidence = "")),
            listOf(listOf(SdkRule(id = "x", name = "y", confidence = "low")))
        )
        // rank("")==1, rank(low)==1 平手 → 保留内置
        assertEquals("x", m.first().name)
    }
}
