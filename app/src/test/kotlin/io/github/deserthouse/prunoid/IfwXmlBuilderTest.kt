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
}
