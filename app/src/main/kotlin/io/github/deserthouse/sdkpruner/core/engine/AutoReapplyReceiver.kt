package io.github.deserthouse.sdkpruner.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 静态注册兜底 receiver：进程活跃时可收到（前台服务活着时本进程不被 cached）
// 主通道是 RuleGuardService 的动态注册
class AutoReapplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
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
