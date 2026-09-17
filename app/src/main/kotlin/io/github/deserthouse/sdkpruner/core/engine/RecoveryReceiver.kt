package io.github.deserthouse.sdkpruner.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.deserthouse.sdkpruner.core.rules.RuleRepository

// 轻量恢复入口（路线图 M3）：UI 无法启动时的救砖通道
// 用法：adb shell am broadcast -a io.github.deserthouse.sdkpruner.action.CLEAR_IFW --ez confirm true
// --ez confirm true 为强制确认位，防止第三方应用随手广播清除用户规则
class RecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!intent.getBooleanExtra(EXTRA_CONFIRM, false)) {
            Log.w("SdkPruner", "recovery: missing confirm extra, ignored")
            return
        }
        Log.w("SdkPruner", "recovery: clearing all IFW rules (adb)")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val n = DisableEngine(context, RuleRepository(context)).clearAllIfw()
                Log.w("SdkPruner", "recovery: cleared $n IFW files")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.deserthouse.sdkpruner.action.CLEAR_IFW"
        const val EXTRA_CONFIRM = "confirm"
    }
}
