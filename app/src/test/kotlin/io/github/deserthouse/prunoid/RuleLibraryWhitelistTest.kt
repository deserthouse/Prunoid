package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.DisableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 第五轮审查 P2：规则库全量清单类组件（activity/service/receiver/provider）必须全部通过
 *  引擎白名单——批W5 的白名单是整表拒绝语义（一个非法名=整次调用失败），规则数据一旦
 *  混入白名单外的组件名，按该 SDK 禁用/恢复会整体失败。native（.so）不进引擎，不计。 */
class RuleLibraryWhitelistTest {

    private fun snapshotText(): String {
        val candidates = listOf(
            File("src/main/assets/rules/snapshot.json"),
            File("app/src/main/assets/rules/snapshot.json"),
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `every manifest-type rule component passes the engine whitelist`() {
        val re = Regex("""\{"type":\s*"(\w+)",\s*"class":\s*"([^"]*)"\s*\}""")
        val text = snapshotText()
        val matches = re.findAll(text).toList()
        assertTrue("snapshot parse found no components", matches.size > 1000)
        val manifest = matches.filter { it.groupValues[1] in setOf("activity", "service", "receiver", "provider") }
        assertTrue(manifest.size > 800)
        val bad = manifest.filter { !DisableEngine.validCompName(it.groupValues[2]) }
        assertEquals("components the engine whitelist would reject: " + bad.take(10), 0, bad.size)
    }

    @Test
    fun `parse covered every component entry in the snapshot`() {
        // 仪器自检：正则命中数必须等于 "class" 字段总数，防解析漏项造成假阴性
        val text = snapshotText()
        val total = Regex(""""class":\s*"""").findAll(text).count()
        assertEquals(total, Regex("""\{"type":\s*"(\w+)",\s*"class":""").findAll(text).count())
    }
}
