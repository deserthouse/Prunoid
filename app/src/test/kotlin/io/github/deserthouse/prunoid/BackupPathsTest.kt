package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.BackupPaths
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 热修 0.31.1（第五轮审查 R5-01/R5-02/R5-04）：
 *  备份路径"生产者-消费者"合同 + 目标白名单的空集语义。 */
class BackupPathsTest {
    private val filesDir = "/data/user/0/io.github.deserthouse.prunoid/files"
    private val dir = "$filesDir/backups"

    @Test
    fun `every listed backup passes the restore whitelist`() {
        // 0.31.0 的回归形态：列举返回裸文件名 → 还原白名单全拒 → 应用内恢复必败。
        // 生产者与消费者只经由 BackupPaths，本断言锁住两者同源。
        val listed = BackupPaths.fromListing(
            filesDir,
            listOf("backup_20261022-071611.tar.gz", "backup_20261028-113250.tar.gz"),
        )
        assertEquals(2, listed.size)
        listed.forEach { assertTrue("not restorable: $it", BackupPaths.isManaged(filesDir, it)) }
    }

    @Test
    fun `the bare-name producer form is rejected by the whitelist`() {
        // 阳性对照：批X2 的返回形态（裸文件名）必须被白名单拒绝——证明上一条不是恒真
        assertFalse(BackupPaths.isManaged(filesDir, "backup_20261028-113250.tar.gz"))
    }

    @Test
    fun `listing keeps only snapshots and sorts newest first`() {
        val listed = BackupPaths.fromListing(
            filesDir,
            listOf(
                " backup_20261022-071611.tar.gz ",
                "backup_x.tar.gz",                      // 不合规名：不可恢复即不列出
                "backup_20261028-113250.tar.gz.bak",
                "rules.json",
                "backup_20261114-034500.tar.gz",
            ),
        )
        assertEquals(
            listOf(
                "$dir/backup_20261114-034500.tar.gz",
                "$dir/backup_20261022-071611.tar.gz",
            ),
            listed,
        )
    }

    @Test
    fun `whitelist rejects traversal, foreign dirs and look-alike names`() {
        listOf(
            // 旧判定（startsWith 前缀 + 后缀 containsMatchIn）放行的穿越形态
            "$dir/../files/backup_20200101-000000.tar.gz",
            "$dir/../../../../sdcard/backup_20200101-000000.tar.gz",
            "$dir/sub/backup_20200101-000000.tar.gz",
            // 外部目录 / 附加段 / 形似名 / 位数不符
            "/sdcard/backup_20200101-000000.tar.gz",
            "$dir/backup_20200101-000000.tar.gz.bak",
            "$dir/xbackup_20200101-000000.tar.gz",
            "$dir/backup_20200101-000000.tar.gz/",
            "$dir/backup_2020010-000000.tar.gz",
            "$dir/backup_20200101-000000.tar.gz\nrm -rf /",
            "",
        ).forEach { assertFalse("accepted: $it", BackupPaths.isManaged(filesDir, it)) }
        assertTrue(BackupPaths.isManaged(filesDir, "$dir/backup_20200101-000000.tar.gz"))
    }

    @Test
    fun `empty target set is a no-op, not a rejection`() {
        // 批W5 的 requireValidTarget 把空集连同非法名一并拒绝 → removeIfw(空集) 失败
        DisableEngine.requireValidTarget("cn.kaiheila", emptySet())
        DisableEngine.requireValidTarget("cn.kaiheila", emptyList())
        DisableEngine.requireValidTarget(
            "cn.kaiheila", listOf("com.bytedance.sdk.openadsdk.sticker.StickerXmlParser"),
        )
    }

    @Test
    fun `empty set does not excuse an invalid package or member`() {
        try {
            DisableEngine.requireValidTarget("bad pkg", emptySet())
            fail("invalid package accepted")
        } catch (_: IllegalArgumentException) {
        }
        try {
            DisableEngine.requireValidTarget("cn.kaiheila", setOf("a;b"))
            fail("invalid member accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
