package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.DisableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批K5：多包现场读脚本契约锁定——分节头前必须有 echo 空行（存量 IFW 文件可能无尾换行，
 *  缺空行时 "==IFW" 分节头粘进上一文件末行，按行前缀解析即丢掉第二个及以后的整包，
 *  批J6.3 P2 实证：pkgs=2 parsed live=1）。字符串级防回退。 */
class ReadLiveScriptTest {

    @Test
    fun `section header is preceded by blank echo`() {
        val script = DisableEngine.buildReadLiveScript(listOf("cn.a", "com.b"))
        assertTrue(
            "分节头前必须有 echo 空行（防无尾换行文件粘行）",
            script.contains("; do echo; echo \"==IFW \$p\"; cat ")
        )
    }

    @Test
    fun `script iterates all packages and ends with total count`() {
        val script = DisableEngine.buildReadLiveScript(listOf("cn.a", "com.b"))
        assertEquals("for p in cn.a com.b", script.substringBefore("; do"))
        assertTrue(script.endsWith("| wc -l"))
        assertTrue(script.contains("echo ==TOTAL"))
    }
}
