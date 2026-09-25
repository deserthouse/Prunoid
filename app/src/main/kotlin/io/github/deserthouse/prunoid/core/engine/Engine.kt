package io.github.deserthouse.prunoid.core.engine

// M2 双引擎：IFW 主（app 无感知、无法自恢复）+ pm disable 辅（跨更新保留、app 可自恢复）
// 显示名资源化后 label 死字段已删（批G1）；UI 层经 stringResource 按引擎映射
enum class Engine {
    IFW,
    PM
}
