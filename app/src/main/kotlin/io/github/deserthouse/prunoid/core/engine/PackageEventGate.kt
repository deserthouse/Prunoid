package io.github.deserthouse.prunoid.core.engine

/** 批Z5（第五轮审查 R5-16）：包事件去重闸——同一包在窗口期内的重复事件只放行第一次。
 *  实测动态接收器会把同一次安装/更新的事件连着收到两次；重应用幂等但每次都要扫描、取锁、写盘。
 *  纯逻辑、无 Android 依赖，时间由调用方传入（可 JVM 测试）。 */
class PackageEventGate(private val windowMs: Long = 1500L) {
    private val last = HashMap<String, Long>()

    /** 返回 true=应当处理；false=窗口期内的重复事件 */
    @Synchronized
    fun admit(pkg: String, now: Long): Boolean {
        val prev = last[pkg]
        if (prev != null && now - prev < windowMs) return false
        last[pkg] = now
        if (last.size > 256) last.entries.removeAll { now - it.value >= windowMs }
        return true
    }
}
