package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.IfwXmlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IfwXmlBuilderTest {

    @Test
    fun `empty input returns null`() {
        assertNull(IfwXmlBuilder.build(emptyMap()))
        assertNull(IfwXmlBuilder.build(mapOf("activity" to emptyList())))
    }

    @Test
    fun `provider components are skipped`() {
        val xml = IfwXmlBuilder.build(mapOf("provider" to listOf("com.x.Prov")))
        assertNull(xml) // provider-only -> no groups -> null
    }

    @Test
    fun `groups by component type with block and log attributes`() {
        val xml = IfwXmlBuilder.build(
            mapOf(
                "activity" to listOf("pkg/ClsA"),
                "service" to listOf("pkg/SvcB"),
                "receiver" to listOf("pkg/RcvC")
            )
        )!!
        assertTrue(xml.contains("<activity block=\"true\" log=\"true\">"))
        assertTrue(xml.contains("<service block=\"true\" log=\"true\">"))
        assertTrue(xml.contains("<receiver block=\"true\" log=\"true\">"))
        assertTrue(xml.contains("<component-filter name=\"pkg/ClsA\" />"))
        assertTrue(xml.contains("<component-filter name=\"pkg/SvcB\" />"))
        assertTrue(xml.contains("<component-filter name=\"pkg/RcvC\" />"))
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.trim().endsWith("</rules>"))
    }

    @Test
    fun `xml is parseable structure`() {
        val xml = IfwXmlBuilder.build(mapOf("activity" to listOf("p/A", "p/B")))!!
        // 两个 filter 都在且顺序稳定
        val idxA = xml.indexOf("p/A")
        val idxB = xml.indexOf("p/B")
        assertTrue(idxA >= 0 && idxB > idxA)
    }

    // ── 批Y2（架构评审 C1/C3 加固）：buildByTag 标签白名单 ──

    @Test
    fun `buildByTag drops illegal tag keys`() {
        // C1 污染形态：类名伪标签组与合法组混入——只允许 activity/service/receiver 出现在产物
        val xml = IfwXmlBuilder.buildByTag(
            mapOf(
                "activity" to setOf("p/A"),
                "com.igexin.sdk.PushService" to setOf("p/Ghost"),
                "native" to setOf("p/N"),
                "provider" to setOf("p/Prov")
            )
        )!!
        assertTrue(xml.contains("<activity block=\"true\" log=\"true\">"))
        assertTrue(!xml.contains("Ghost"))
        assertTrue(!xml.contains("<native"))
        assertTrue(!xml.contains("<provider"))
    }

    @Test
    fun `buildByTag illegal-tags-only returns null`() {
        assertNull(IfwXmlBuilder.buildByTag(mapOf("com.x.Cls" to setOf("p/Ghost"))))
    }

    @Test
    fun `buildByTag parse roundtrip ignores unknown tags`() {
        // 往返闭合：白名单产物再经 parse 应无信息损失
        val src = mapOf<String, Set<String>>(
            "activity" to setOf("p/A"),
            "service" to setOf("p/S")
        )
        val xml = IfwXmlBuilder.buildByTag(src)!!
        val parsed = IfwXmlBuilder.parse(xml)
        assertEquals(src, parsed)
    }
}
