package io.github.deserthouse.prunoid.core.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 批Z2（架构评审 A4）：进程级全局写锁——本应用全部 root 写入口共用同一把锁，
// 手动操作（详情/档案/批量/恢复/应急清除/声明写入）与后台自动重应用串行化，
// 不再各自为政（0.18.1 的 AutoReapply 私有 Mutex 只护住后台一条路径）。
//
// 边界如实：仅本进程互斥。外部 root 工具不在锁内——写前校验与写后读回检测变化，
// 不宣称跨工具互斥（架构报告 §6.3 口径）。
object OpCoordinator {

    private val gate = Mutex()

    suspend fun <T> write(block: suspend () -> T): T = gate.withLock { block() }
}
