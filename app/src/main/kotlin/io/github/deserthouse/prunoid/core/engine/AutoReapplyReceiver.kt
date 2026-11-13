package io.github.deserthouse.prunoid.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 静态注册兜底 receiver：进程活跃时可收到（前台服务活着时本进程不被 cached）
// 主通道是 RuleGuardService 的动态注册
class AutoReapplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        // 批X4（M08）：配置读取一并移入 goAsync 后台任务（原 runBlocking 在主线程等待
        // DataStore）；PendingResult 在 finally 必达释放
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 批X3（审查 R10/PN-02）：静态兜底通道对齐三元组（audit/open 下静默）
                val s = io.github.deserthouse.prunoid.core.rules.SettingsRepository(context).settings.first()
                val allowed = s.autoReapply && s.workMode == "root" && s.reapplyMode == "realtime"
                if (!allowed) return@launch
                if (AutoReapply.shouldHandle(context, pkg)) {
                    Log.d("SdkPruner", "static receiver: package event $pkg")
                    AutoReapply.process(context, pkg)
                }
            } catch (e: Exception) {
                Log.e("SdkPruner", "static receiver failed for $pkg", e)
            } finally {
                pending.finish()
            }
        }
    }
}
