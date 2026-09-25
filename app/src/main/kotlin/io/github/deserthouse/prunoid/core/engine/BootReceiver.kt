package io.github.deserthouse.prunoid.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// 批拉通#13：BOOT_COMPLETED 后恢复守护前台服务（realtime 档依赖包事件广播，
// 重启后到下次打开 app 之间存在盲区）。仅 autoReapply 开启时拉起。
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = runCatching {
            runBlocking(Dispatchers.IO) {
                io.github.deserthouse.prunoid.core.rules.SettingsRepository(context)
                    .settings.first().autoReapply
            }
        }.getOrDefault(false)
        if (on) {
            // 批G4 热修：BOOT 重投递到达时 A16 后台 FGS 豁免窗口可能已关
            // （ForegroundServiceStartNotAllowedException 曾致 receiver 崩溃弹窗）；
            // 拉起失败不抛——主界面 ensureRuleGuard() 是兜底拉起路径
            try {
                context.startForegroundService(Intent(context, RuleGuardService::class.java))
            } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
