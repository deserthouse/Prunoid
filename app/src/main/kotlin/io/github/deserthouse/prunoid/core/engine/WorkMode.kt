package io.github.deserthouse.prunoid.core.engine

/**
 * 批N：一级工作方式契约（策略架构）。
 * 每个工作方式声明能力、状态源与恢复路径；UI 只对契约分派，不感知具体模式。
 * HookWorkMode（LSPosed 声明式）为 P 批预留——新增实现时实现本接口即可，骨架零改动。
 */
enum class WorkModeId { ROOT, AUDIT }   // HOOK 预留（P 批）

data class WorkModeCapabilities(
    val identify: Boolean = true,        // 扫描/识别/统计（两种模式都可用）
    val disablePerApp: Boolean = false,  // 逐应用禁用/恢复（root）
    val declareGlobal: Boolean = false   // 声明式全局画圈（hook，P 批）
)

data class WorkModeInfo(
    val id: WorkModeId,
    val capabilities: WorkModeCapabilities
) {
    companion object {
        val ROOT = WorkModeInfo(WorkModeId.ROOT, WorkModeCapabilities(identify = true, disablePerApp = true))
        val AUDIT = WorkModeInfo(WorkModeId.AUDIT, WorkModeCapabilities(identify = true))
        fun fromTag(tag: String): WorkModeInfo = if (tag == "audit") AUDIT else ROOT
    }
}
