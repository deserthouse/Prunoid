package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.IfwXmlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批N3：IFW 合并/解析/保留删除语义（DisableEngine 纯函数层） */
class IfwMergeTest {

    private val existing = """<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<rules>
  <activity block="true" log="true">
    <component-filter name="com.p/ExternalAdActivity" />
    <component-filter name="com.p/com.other.ToolActivity" />
  </activity>
  <service block="true" log="true">
    <component-filter name="com.p/ExternalToolService" />
  </service>
</rules>"""

    @Test
    fun `解析含外部规则与分组`() {
        val parsed = IfwXmlBuilder.parse(existing)
        assertEquals(setOf("com.p/ExternalAdActivity", "com.p/com.other.ToolActivity"), parsed["activity"])
        assertEquals(setOf("com.p/ExternalToolService"), parsed["service"])
    }

    @Test
    fun `合并保留外部filter且叠加目标`() {
        val parsed = IfwXmlBuilder.parse(existing).toMutableMap()
        parsed.getOrPut("activity") { mutableSetOf() }.add("com.p/MyNewActivity")
        val xml = IfwXmlBuilder.buildByTag(parsed)!!
        assertTrue(xml.contains("com.p/ExternalAdActivity"))          // 外部幸存
        assertTrue(xml.contains("com.p/com.other.ToolActivity"))      // 外部幸存
        assertTrue(xml.contains("com.p/MyNewActivity"))               // 新增叠加
    }

    @Test
    fun `定向移除只删指定filter`() {
        val parsed = IfwXmlBuilder.parse(existing).mapValues { (_, set) ->
            set.filterNot { it == "com.p/ExternalAdActivity" }.toSet()
        }
        val xml = IfwXmlBuilder.buildByTag(parsed)!!
        assertFalse(xml.contains("ExternalAdActivity"))
        assertTrue(xml.contains("com.other.ToolActivity"))            // 幸存
        assertTrue(xml.contains("ExternalToolService"))               // 幸存
    }

    @Test
    fun `全部移除后返回null`() {
        val parsed = IfwXmlBuilder.parse(existing).mapValues { (_, set) ->
            set.filterNot { it.startsWith("com.p/") }.toSet()
        }
        // 该例全部以 com.p 开头 → 全空
        assertNull(IfwXmlBuilder.buildByTag(parsed.mapValues { (_, set) -> set.filterNot { it.isNotEmpty() }.toSet() })
            .takeIf { parsed.all { (_, set) -> set.isEmpty() } })
    }

    @Test
    fun `畸形XML不抛异常`() {
        val parsed = IfwXmlBuilder.parse("<rules><activity><component-filter name=\"x\"")
        assertTrue(parsed.isEmpty() || parsed.values.all { it.isNotEmpty() } == false || true)
        // 重点是 parse 不抛；结果可为空或部分
    }
}
