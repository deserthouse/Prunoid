package io.github.deserthouse.prunoid.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 批拉通#13：BOOT_COMPLETED 后恢复守护前台服务（realtime 档依赖包事件广播，
// 重启后到下次打开 app 之间存在盲区）。仅 autoReapply 开启时拉起。
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 批S1c（N-1 定案修复）：条件对齐 MainActivity.ensureRuleGuard 三元组
        // （autoReapply && root && realtime）。旧口径只查 autoReapply（默认 true）——
        // 默认 open 档下 BOOT 也会拉起服务，与主界面随后的 stopService 竞态即 N-1 崩溃源；
        // 且系统对 force-stop 过的 app 在下次显式启动时补发 BOOT_COMPLETED（平台文档行为），
        // "强停后再打开"与"重启后打开"都会踩中此路径
        // 批X4（M08）：配置读取移入 goAsync 后台任务（原 runBlocking 阻塞主线程）；
        // BOOT_COMPLETED 让进程处于临时豁免态，goAsync 窗口内拉起 FGS 仍受豁免保护
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val on = runCatching {
                    val s = io.github.deserthouse.prunoid.core.rules.SettingsRepository(context).settings.first()
                    s.autoReapply && s.workMode == "root" && s.reapplyMode == "realtime"
                }.getOrDefault(false)
                if (!on) return@launch
                // 批G4 热修：FGS 豁免窗口可能已关——拉起失败不抛（主界面 ensureRuleGuard 兜底）
                try {
                    android.util.Log.d("SdkPruner", "bootreceiver: action=${intent.action} starting guard")
                    context.startForegroundService(Intent(context, RuleGuardService::class.java))
                } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
                } catch (_: SecurityException) {
                }
            } finally {
                pending.finish()
            }
        }
    }
}
