package io.github.deserthouse.sdkpruner

import io.github.deserthouse.sdkpruner.core.rules.RuleMerger
import io.github.deserthouse.sdkpruner.core.rules.SdkRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleMergerTest {

    private fun rule(id: String, name: String = id, prefixes: List<String> = emptyList()) =
        SdkRule(id = id, name = name, packPrefixes = prefixes)

    @Test
    fun `empty subscription returns built-in as-is`() {
        val built = listOf(rule("a"), rule("b"))
        assertEquals(built, RuleMerger.merge(built, emptyList()))
    }

    @Test
    fun `same id overrides built-in`() {
        val built = listOf(rule("a", "old"), rule("b"))
        val sub = listOf(rule("a", "new"))
        val merged = RuleMerger.merge(built, sub)
        assertEquals(2, merged.size)
        assertEquals("new", merged.first { it.id == "a" }.name)
    }

    @Test
    fun `new id appended after built-in`() {
        val built = listOf(rule("a"))
        val sub = listOf(rule("x", prefixes = listOf("com.x.")))
        val merged = RuleMerger.merge(built, sub)
        assertEquals(listOf("a", "x"), merged.map { it.id })
    }

    @Test
    fun `mixed override and append`() {
        val built = listOf(rule("a", "oldA"), rule("b"))
        val sub = listOf(rule("b", "newB"), rule("c"))
        val merged = RuleMerger.merge(built, sub)
        assertEquals(3, merged.size)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("newB", merged.first { it.id == "b" }.name)
    }
}
