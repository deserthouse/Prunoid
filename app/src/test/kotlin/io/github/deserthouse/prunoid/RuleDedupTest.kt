package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.rules.ComponentAnchor
import io.github.deserthouse.prunoid.core.rules.SdkRule
import io.github.deserthouse.prunoid.core.rules.dedupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批R5：同实体别名去重的保守合并语义 */
class RuleDedupTest {

    private fun rule(id: String, name: String, safe: Boolean, conf: String, comps: Int = 1) =
        SdkRule(
            id = id, name = name, company = "C", category = "push",
            packPrefixes = listOf("com.$id."),
            components = (1..comps).map { ComponentAnchor("service", "$id.cls$it") },
            safeToBlock = safe, confidence = conf, sources = listOf("src-$id"), contributors = listOf("c-$id")
        )

    @Test
    fun `跨语言别名合并且安全结论取保守`() {
        val out = dedupRules(
            listOf(
                rule("aurora", "Aurora Push", safe = true, conf = "high", comps = 5),
                rule("jpush", "极光推送", safe = false, conf = "high", comps = 3)
            ),
            aliases = mapOf("Aurora Push" to "极光推送")
        )
        assertEquals(1, out.size)
        val r = out.single()
        assertEquals("极光推送", r.name)
        assertFalse(r.safeToBlock)              // AND：只升不造
        assertEquals(8, r.components.size)      // 并集
        assertEquals(setOf("com.aurora.", "com.jpush."), r.packPrefixes.toSet())
    }

    @Test
    fun `同名组置信度取最低`() {
        val out = dedupRules(listOf(
            rule("a", "Signal360", safe = true, conf = "high"),
            rule("b", "Signal360", safe = true, conf = "low")
        ))
        assertEquals(1, out.size)
        assertEquals("low", out.single().confidence)
        assertTrue(out.single().safeToBlock)
    }

    @Test
    fun `无关规则不受影响`() {
        val inp = listOf(rule("x", "Foo", safe = true, conf = "high"), rule("y", "Bar", safe = false, conf = "low"))
        assertEquals(inp, dedupRules(inp))
    }
}
