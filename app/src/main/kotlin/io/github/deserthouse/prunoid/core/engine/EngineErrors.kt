package io.github.deserthouse.prunoid.core.engine

import io.github.deserthouse.prunoid.R

/** 批Y5（第五轮审查 R5-11）：引擎/订阅失败原因的分类——纯函数。
 *  VM 据此取本地化文案；原始串仍进结果明细（OperationItem.detail）与日志供排障，
 *  不再直接拼进用户可见消息（此前界面出现过 "ifw remove failed"、"Failed to connect to…"）。 */
enum class EngineErrorKind(val res: Int) {
    INVALID_TARGET(R.string.err_invalid_target),
    SYSTEM_BLOCKED(R.string.err_system_blocked),
    IFW_WRITE(R.string.err_ifw_write),
    IFW_REMOVE(R.string.err_ifw_remove),
    PM_COMMAND(R.string.err_pm_command),
    BACKUP_PATH(R.string.err_backup_path),
    BACKUP_UNPACK(R.string.err_backup_unpack),
    BACKUP_APPLY(R.string.err_backup_apply),
    BACKUP_CREATE(R.string.err_backup_create),
    LEDGER_CORRUPTED(R.string.err_ledger_corrupted),
    LEDGER_WRITE(R.string.err_ledger_write),
    TOO_LARGE(R.string.err_too_large),
    HTTP(R.string.err_http),
    NETWORK(R.string.err_network),
    EMPTY_SOURCE(R.string.err_empty_source),
    CACHE_WRITE(R.string.err_cache_write),
    UNKNOWN(R.string.err_unknown);

    companion object {
        private val HTTP_CODE = Regex("HTTP (\\d{3})")

        fun classify(raw: String?): EngineErrorKind {
            val m = raw?.trim()?.lowercase() ?: return UNKNOWN
            fun has(vararg keys: String) = keys.any { it in m }
            return when {
                has("invalid package name", "invalid component name") -> INVALID_TARGET
                has("blocked by whitelist") -> SYSTEM_BLOCKED
                has("ifw write failed", "ifw rewrite failed") -> IFW_WRITE
                has("ifw remove failed") -> IFW_REMOVE
                has("pm disable failed", "pm disable:", "pm enable failed", "pm enable:", "components failed") -> PM_COMMAND
                has("backup path outside") -> BACKUP_PATH
                has("restore unpack failed") -> BACKUP_UNPACK
                has("restore apply failed") -> BACKUP_APPLY
                has("backup not created", "backup listing failed") -> BACKUP_CREATE
                has("ledger corrupted") -> LEDGER_CORRUPTED
                has("ledger atomic replace failed") -> LEDGER_WRITE
                has("response too large") -> TOO_LARGE
                has("http ") -> HTTP
                has("failed to connect", "unable to resolve host", "timeout", "timed out", "ssl",
                    "unexpected end of stream", "connection") -> NETWORK
                has("no entries") -> EMPTY_SOURCE
                has("atomic replace failed") -> CACHE_WRITE
                else -> UNKNOWN
            }
        }

        /** HTTP 状态码（仅 [HTTP] 类有意义） */
        fun httpCode(raw: String?): String? = HTTP_CODE.find(raw ?: "")?.groupValues?.get(1)
    }
}
