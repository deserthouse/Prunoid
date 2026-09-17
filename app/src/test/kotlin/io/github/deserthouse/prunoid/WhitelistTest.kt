package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.DisableEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistTest {

    @Test
    fun `framework and oem packages are forbidden`() {
        assertTrue(DisableEngine.isForbidden("android"))
        assertTrue(DisableEngine.isForbidden("com.android.settings"))
        assertTrue(DisableEngine.isForbidden("com.google.android.gms"))
        assertTrue(DisableEngine.isForbidden("com.miui.home"))
        assertTrue(DisableEngine.isForbidden("com.huawei.hms.core"))
        assertTrue(DisableEngine.isForbidden("org.chromium.webview"))
    }

    @Test
    fun `third-party apps are allowed`() {
        assertFalse(DisableEngine.isForbidden("github.tornaco.android.thanos"))
        assertFalse(DisableEngine.isForbidden("me.app.xad.skip"))
        assertFalse(DisableEngine.isForbidden("test.pruner.sample"))
    }

    @Test
    fun `prefix collision does not overreach`() {
        // com.androidx 第三方伪装（罕见但原则要守住）：前缀按段匹配
        // 注意 "androidx." 拦截 androidx 伪包；但 "com.example.androidx" 不应被拦
        assertFalse(DisableEngine.isForbidden("com.example.androidx"))
    }
}
