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
        // 自动重应用开关关闭时，静态兜底通道一并静默
        val autoOn = kotlinx.coroutines.runBlocking {
            io.github.deserthouse.prunoid.core.rules.SettingsRepository(context).settings.first().autoReapply
        }
        if (!autoOn) return
        if (AutoReapply.shouldHandle(context, pkg)) {
            Log.d("SdkPruner", "static receiver: package event $pkg")
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    AutoReapply.process(context, pkg)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
