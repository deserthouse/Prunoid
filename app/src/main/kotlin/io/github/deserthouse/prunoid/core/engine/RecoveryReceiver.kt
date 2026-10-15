package io.github.deserthouse.prunoid.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.deserthouse.prunoid.core.rules.RuleRepository

// 轻量恢复入口（路线图 M3）：UI 无法启动时的救砖通道
// 用法：adb shell am broadcast -a io.github.deserthouse.prunoid.action.CLEAR_IFW --ez confirm true
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
                // 批Y3（架构评审 C4）：应急清除=终止 IFW 自动管理——失效账本条目同步移除
                // （PM 条目保留），防止包更新按账本复活刚清掉的规则
                AppliedRulesStore(context).removeEngine(Engine.IFW.name)
                Log.w("SdkPruner", "recovery: cleared $n IFW files, IFW ledger entries removed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.deserthouse.prunoid.action.CLEAR_IFW"
        const val EXTRA_CONFIRM = "confirm"
    }
}
