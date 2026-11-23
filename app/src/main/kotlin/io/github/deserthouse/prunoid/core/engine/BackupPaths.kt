package io.github.deserthouse.prunoid.core.engine

/**
 * 备份路径的唯一事实源（热修 0.31.1，第五轮审查 R5-01）。
 *
 * 批X2 把 [DisableEngine.listBackups] 的返回值从绝对路径改成裸文件名，而批W5 的还原
 * 白名单按"backups 目录绝对路径 + 文件名"判定——两处各自拼路径、没有共享合同，0.31.0
 * 上应用内"恢复所选归档"必然失败。生产者与消费者改为都只经由本对象，并由
 * BackupPathsTest 锁定"列举输出恒通过还原校验"。
 *
 * 还原白名单同时收紧为**恰为** backups 目录下的合法快照名：原 `startsWith(前缀)` +
 * `containsMatchIn(后缀)` 判定会放行 `backups/../x/backup_….tar.gz` 一类穿越路径。
 */
internal object BackupPaths {
    /** 时点快照文件名：与 [DisableEngine.backup] 的命名约定同源 */
    val NAME_RE: Regex = Regex("^backup_\\d{8}-\\d{6}\\.tar\\.gz$")

    fun dir(filesDir: String): String = "$filesDir/backups"

    fun file(filesDir: String, name: String): String = "${dir(filesDir)}/$name"

    /** `ls -1` 输出（每行一个文件名）→ 受管快照的绝对路径，新→旧；非快照文件不进列表。 */
    fun fromListing(filesDir: String, lines: List<String>): List<String> =
        lines.map { it.trim() }
            .filter { NAME_RE.matches(it) }
            .map { file(filesDir, it) }
            .sortedDescending()

    /** 还原白名单：无 `..`、无子目录、无附加段，父目录恰为本应用 backups 目录。 */
    fun isManaged(filesDir: String, path: String): Boolean {
        val name = path.substringAfterLast('/')
        return NAME_RE.matches(name) && path == file(filesDir, name)
    }
}
