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
        // 批X3（审查 R10/PN-02）：静态兜底通道对齐三元组（原只查 autoReapply，audit/open 下仍会走重应用）
        val allowed = kotlinx.coroutines.runBlocking {
            val s = io.github.deserthouse.prunoid.core.rules.SettingsRepository(context).settings.first()
            s.autoReapply && s.workMode == "root" && s.reapplyMode == "realtime"
        }
        if (!allowed) return
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
