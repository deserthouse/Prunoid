package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.DisableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批W5（M09/M11）：名称白名单与 XML 转义回读——壳插值与属性插值前的统一防线。 */
class TargetNameValidationTest {

    @Test
    fun `valid android names pass`() {
        assertTrue(DisableEngine.validPkgName("cn.kaiheila"))
        assertTrue(DisableEngine.validPkgName("com.jingyao.easybike"))
        assertTrue(DisableEngine.validCompName("com.ss.android.socialbase.appdownloader.view.DownloadTaskDeleteActivity"))
        assertTrue(DisableEngine.validCompName("com.example.Stub\$Inner"))
    }

    @Test
    fun `shell metacharacters and traversal are rejected`() {
        assertFalse(DisableEngine.validPkgName("com.a; touch /data/local/tmp/pwned"))
        assertFalse(DisableEngine.validPkgName("com.a\$(id)"))
        assertFalse(DisableEngine.validPkgName("../evil"))
        assertFalse(DisableEngine.validCompName("p/X; pm install /sdcard/evil.apk"))
        assertFalse(DisableEngine.validCompName("p/X && id"))
        assertFalse(DisableEngine.validCompName("p/has space"))
        assertFalse(DisableEngine.validCompName(""))
    }

    @Test
    fun `xml escaping round-trips through parse`() {
        // 转义后属性值经 XmlPullParser 解析还原原文（含引号/尖括号的对抗名）
        val raw = "p/X<>&\"q"
        val xml = "  <service block=\"true\" log=\"true\">\n" +
            "    <component-filter name=\"" + raw.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;") + "\" />\n  </service>"
        val parsed = IfwXmlBuilder.parse(xml)
        assertEquals(setOf(raw), parsed["service"])
    }
}
