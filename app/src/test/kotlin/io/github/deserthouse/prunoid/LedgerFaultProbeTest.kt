package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 第五轮审查 P2：账本故障与边界探针（真实生产类 AppliedRulesStore）。
 *  设备级注入（锁文件/断电）见审查记录；此处只放跨平台可确定性注入的点。 */
class LedgerFaultProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun merge(s: AppliedRulesStore, pkg: String, comp: String) =
        s.recordMerge(pkg, "IFW", listOf(comp), mapOf(comp to "activity"))

    /** 写到一半被杀会留下 .tmp：下次启动必须读主文件而不是把半成品当账本，且下次写要覆盖它。 */
    @Test
    fun `leftover tmp from an interrupted write is ignored and replaced by the next save`() {
        val dir = tmp.newFolder("leftover")
        val f = File(dir, "led.json")
        merge(AppliedRulesStore(f), "pkg.a", "p/A")
        File(dir, "led.json.tmp").writeText("{\"entries\":{\"pkg.ghost\"") // 半成品
        val s = AppliedRulesStore(f)
        assertNotNull(s.get("pkg.a"))
        assertNull("半成品不得进入账本", s.get("pkg.ghost"))
        merge(s, "pkg.b", "p/B")
        assertEquals(setOf("pkg.a", "pkg.b"), s.all().keys)
        assertFalse("下次写后 tmp 必须消失", File(dir, "led.json.tmp").exists())
    }

    /** 写入失败必须上抛，且磁盘上的旧账本一字不改。注入点：把 .tmp 路径占成目录，FileOutputStream 必失败。 */
    @Test
    fun `write failure surfaces and the old ledger on disk stays intact`() {
        val dir = tmp.newFolder("wfail")
        val f = File(dir, "led.json")
        val s = AppliedRulesStore(f)
        merge(s, "pkg.a", "p/A")
        val before = f.readText()
        File(dir, "led.json.tmp").apply { mkdir(); resolve("blocker").writeText("x") }
        try {
            merge(s, "pkg.b", "p/B")
            fail("expected the write to fail")
        } catch (e: Exception) {
            // FileOutputStream(tmp) 失败
        }
        assertEquals(before, f.readText())
        assertEquals("失败的那次不得落盘", setOf("pkg.a"), AppliedRulesStore(f).all().keys)
    }

    /** 现状刻画（仅 Windows 可确定性注入；第五轮审查 R5-08）：0.30.1 的回退分支——
     *  "首次改名失败 → 删旧文件成功 → 第二次改名再失败"——旧账本被删、新内容只剩 .tmp、
     *  主文件缺失=下次启动读到空账本；而代码注释与 0.30.1 发布说明都称"任何一步失败旧账本完整保留"。
     *  注入手法：目标 File 子类在 delete() 里先放开对旧账本的占用（首次 rename 因占用失败、delete 得以成功），
     *  再用句柄占住 .tmp（Windows 不允许改名被占用的文件），使第二次 rename 失败。修复后本测试应翻转。 */
    @Test
    fun `characterization - fallback path loses the old ledger when the second rename fails`() {
        assumeTrue(System.getProperty("os.name").lowercase().startsWith("windows"))
        val dir = tmp.newFolder("fallback")
        val real = File(dir, "led.json")
        merge(AppliedRulesStore(real), "pkg.a", "p/A")
        var target: java.io.FileInputStream? = java.io.FileInputStream(real)
        var heldTmp: java.io.FileInputStream? = null
        val hooked = object : File(dir, "led.json") {
            override fun delete(): Boolean {
                target!!.close()
                val ok = super.delete()
                heldTmp = java.io.FileInputStream(File(dir, "led.json.tmp"))
                return ok
            }
        }
        try {
            merge(AppliedRulesStore(hooked), "pkg.b", "p/B")
            fail("expected the save to fail")
        } catch (e: Exception) {
            // 回退分支走完仍失败 → error()
        } finally {
            heldTmp?.close()
            target?.close()
        }
        assertFalse("old ledger survives only if the published claim held", real.exists())
        assertEquals("next launch reads an empty ledger", 0, AppliedRulesStore(real).all().size)
        assertTrue("new content survives only in tmp", File(dir, "led.json.tmp").exists())
    }

    /** 现状刻画（非期望行为，审查 R5-09）：空主文件被当作合法空账本而不是损坏。
     *  批W2 事故正是"空文件=空账本"把静默清账洗成合法态；原子改名后主文件不可能合法为空，
     *  若将来改为按损坏处理，更新本测试。 */
    @Test
    fun `characterization - a blank ledger file is read as a legit empty ledger`() {
        val f = tmp.newFile("blank.json")
        assertEquals(0, AppliedRulesStore(f).all().size)
        assertEquals(false, AppliedRulesStore(f).corrupted)
    }
}
