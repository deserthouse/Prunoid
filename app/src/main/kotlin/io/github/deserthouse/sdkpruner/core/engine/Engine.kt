package io.github.deserthouse.sdkpruner.core.engine

// M2 双引擎：IFW 主（app 无感知、无法自恢复）+ pm disable 辅（跨更新保留、app 可自恢复）
enum class Engine(val label: String) {
    IFW("IFW（无感知）"),
    PM("pm disable（跨更新）")
}
