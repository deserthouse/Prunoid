package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

/** 第五轮审查 P2：账本故障与边界探针（真实生产类 AppliedRulesStore）。
 *  设备级注入（锁文件/断电）见审查记录；此处只放跨平台可确定性注入的点，
 *  以及仅 Windows 可注入的点（Java 的文件句柄不带 share-delete，可让 rename 确定性失败）。 */
class LedgerFaultProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

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

    /** 批Y1（R5-08）：覆盖改名被拒、旧文件也挪不动 → 整体失败，但旧账本原封不动。
     *  0.30.1～0.31.1 的实现在这条路上会先删掉旧账本（第五轮审查 JVM 实证）。 */
    @Test
    fun `fallback keeps the old ledger when both rename and move-away are refused`() {
        assumeTrue(windows)
        val dir = tmp.newFolder("fallback")
        val real = File(dir, "led.json")
        merge(AppliedRulesStore(real), "pkg.a", "p/A")
        val before = real.readText()
        FileInputStream(real).use {
            try {
                merge(AppliedRulesStore(real), "pkg.b", "p/B")
                fail("expected the save to fail")
            } catch (e: Exception) {
                // 回退分支走完仍失败 → error()
            }
        }
        assertEquals("old ledger must be untouched", before, real.readText())
        assertEquals(setOf("pkg.a"), AppliedRulesStore(real).all().keys)
        assertFalse("no .old may be left behind", File(dir, "led.json.old").exists())
    }

    /** 批Y1（R5-08）：覆盖改名被拒、但旧文件能挪走 → 旧文件先进 .old，新文件就位后才删 .old。 */
    @Test
    fun `fallback moves the old ledger aside and only drops it after the new one lands`() {
        assumeTrue(windows)
        val dir = tmp.newFolder("fallback-ok")
        val real = File(dir, "led.json")
        merge(AppliedRulesStore(real), "pkg.a", "p/A")
        val held = FileInputStream(real)
        var calls = 0
        // recordMerge 内 load() 先调一次 exists()；save() 里改名失败后的那一次才是放行点
        val hooked = object : File(dir, "led.json") {
            override fun exists(): Boolean {
                calls++
                if (calls == 2) held.close()
                return super.exists()
            }
        }
        try {
            merge(AppliedRulesStore(hooked), "pkg.b", "p/B")
        } finally {
            held.close()
        }
        assertEquals(setOf("pkg.a", "pkg.b"), AppliedRulesStore(real).all().keys)
        assertFalse("new ledger landed, .old must be gone", File(dir, "led.json.old").exists())
    }

    /** 批Y1（R5-08）：主文件缺失而 .old 在位（回滚失败/中途被杀）→ 认领 .old，不得读成空账本。 */
    @Test
    fun `missing main file with a leftover old copy is adopted`() {
        val dir = tmp.newFolder("adopt")
        val real = File(dir, "led.json")
        merge(AppliedRulesStore(real), "pkg.old", "p/O")
        File(dir, "led.json.old").writeText(real.readText())
        real.delete()
        assertEquals(setOf("pkg.old"), AppliedRulesStore(real).all().keys)
        assertTrue("old copy moved back to the main name", real.exists())
        assertFalse(File(dir, "led.json.old").exists())
    }

    /** 同上，但 .old 挪不回主文件名（被占用）——仍按 .old 的内容读，不丢恢复依据。 */
    @Test
    fun `adoption reads the old copy even when it cannot be moved back`() {
        assumeTrue(windows)
        val dir = tmp.newFolder("adopt-held")
        val real = File(dir, "led.json")
        merge(AppliedRulesStore(real), "pkg.old", "p/O")
        val old = File(dir, "led.json.old")
        old.writeText(real.readText())
        real.delete()
        FileInputStream(old).use {
            assertEquals(setOf("pkg.old"), AppliedRulesStore(real).all().keys)
        }
    }

    /** 现状刻画（非期望行为，审查 R5-09）：空主文件被当作合法空账本而不是损坏。
     *  批W2 事故正是"空文件=空账本"把静默清账洗成合法态；原子改名后主文件不可能合法为空。 */
    @Test
    fun `characterization - a blank ledger file is read as a legit empty ledger`() {
        val f = tmp.newFile("blank.json")
        assertEquals(0, AppliedRulesStore(f).all().size)
        assertEquals(false, AppliedRulesStore(f).corrupted)
    }
}
