package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.rules.SdkRule
import io.github.deserthouse.prunoid.core.scanner.PrefixMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixMatcherTest {

    private val rules = listOf(
        SdkRule(id = "pangle", name = "Pangle", packPrefixes = listOf("com.bytedance.sdk.openadsdk.")),
        SdkRule(id = "gdt", name = "GDT", packPrefixes = listOf("com.qq.e.ads", "com.qq.e.comm")),
        SdkRule(id = "jpush", name = "JPush", packPrefixes = listOf("cn.jpush.android.")),
        // 单段前缀（通配桶）
        SdkRule(id = "miuiish", name = "Miui", packPrefixes = listOf("miui")),
        // 带前导点的脏前缀
        SdkRule(id = "adswizz", name = "AdsWizz", packPrefixes = listOf(".adswizz."))
    )

    private val matcher = PrefixMatcher(rules)

    @Test
    fun `exact prefix hits`() {
        assertEquals(setOf("pangle"), matcher.match("com.bytedance.sdk.openadsdk.stub.activity.StubActivity"))
    }

    @Test
    fun `prefix must not overreach segment boundary`() {
        // com.bytedance.otherapp 不是 com.bytedance.sdk 前缀
        assertTrue(matcher.match("com.bytedance.otherapp.Main").isEmpty())
    }

    @Test
    fun `two rules with sibling prefixes both hit`() {
        val hits = matcher.match("com.qq.e.ads.RewardvideoLandscapeADActivity")
        assertEquals(setOf("gdt"), hits)
    }

    @Test
    fun `single segment prefix in wildcard bucket`() {
        assertEquals(setOf("miuiish"), matcher.match("miui.util.Foo"))
    }

    @Test
    fun `leading dot prefix normalized`() {
        assertEquals(setOf("adswizz"), matcher.match("com.foo.adswizz.Bar"))
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(matcher.match("androidx.activity.ComponentActivity").isEmpty())
    }
}
