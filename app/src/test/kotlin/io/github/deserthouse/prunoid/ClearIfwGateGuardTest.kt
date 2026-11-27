package io.github.deserthouse.prunoid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.33.1：设置页"清除全部 IFW"入口的源码守卫。
 *
 *  背景（发版冒烟门禁第 17 条实测）：未获得 root 授权时，详情页/管理页的入口都置灰，唯独这里可点、
 *  可进确认框，确认后才回一句拒绝。守卫按文本判断，是护栏不是证明；带阳性对照防恒真。 */
class ClearIfwGateGuardTest {

    private fun sourceRoot(): File =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }

    /** 这段代码里，"清除全部 IFW"按钮的 enabled 是否同时受审计模式与 root 授权约束 */
    fun gated(block: String): Boolean =
        "val blocked = auditMode || rootMissing" in block &&
            "val rootMissing = !auditMode && !st.rootGranted" in block &&
            Regex("""enabled = !st\.busy && !blocked""").containsMatchIn(block)

    @Test
    fun `detector accepts a gated button and rejects the old audit-only one`() {
        val good = """
            val auditMode = st.workMode.id == WorkModeId.AUDIT
            val rootMissing = !auditMode && !st.rootGranted
            val blocked = auditMode || rootMissing
            TextButton(onClick = { confirmClearIfw = true }, enabled = !st.busy && !blocked) {}
        """.trimIndent()
        val old = """
            val auditMode = st.workMode.id == WorkModeId.AUDIT
            TextButton(onClick = { confirmClearIfw = true }, enabled = !st.busy && !auditMode) {}
        """.trimIndent()
        assertTrue(gated(good))
        assertFalse(gated(old))
    }

    @Test
    fun `clear all ifw button is greyed without root and says where to grant it`() {
        val text = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/SettingsSections.kt").readText()
        val a = text.indexOf("var confirmClearIfw by remember")
        val b = text.indexOf("if (confirmClearIfw) {")
        assertTrue("RecoveryCards anchors not found", a in 0 until b)
        val block = text.substring(a, b)
        assertTrue("clear-IFW button must be gated on audit mode and root grant:\n$block", gated(block))
        // 置灰时要给出原因：审计模式的说明与未授权的授权路径
        assertTrue(block.contains("R.string.recovery_clear_audit_hint"))
        assertTrue(block.contains("R.string.recovery_clear_root_hint"))
        // 命令边界仍有兜底：VM 入口在无 root 时拒绝
        val vm = sourceRoot().resolve("io/github/deserthouse/prunoid/ui/AppViewModel.kt").readText()
        val c = vm.indexOf("fun clearAllIfw(")
        assertTrue("clearAllIfw must keep its root gate", vm.substring(c, c + 600).contains("if (!rootGate())"))
    }
}
