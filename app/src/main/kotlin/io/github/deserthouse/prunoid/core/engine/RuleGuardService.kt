package io.github.deserthouse.prunoid.core.engine

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// M3-R8：规则守护前台服务——保持进程活跃，使包安装/更新广播可投递
// （Android 15+ 对 cached 进程的 manifest receiver 强制跳过：Background execution not allowed）
// 动态注册两个 receiver：包事件（自动重应用）+ 应急清除（无 data scheme，须独立 filter）
class RuleGuardService : Service() {

    private lateinit var engine: DisableEngine

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (AutoReapply.shouldHandle(context, pkg)) {
                Log.d("SdkPruner", "guard: package event $pkg")
                AutoReapply.process(context, pkg)
            }
        }
    }

    private val recoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.getBooleanExtra(RecoveryReceiver.EXTRA_CONFIRM, false)) {
                Log.w("SdkPruner", "guard: recovery missing confirm, ignored")
                return
            }
            Log.w("SdkPruner", "guard: recovery clearing all IFW")
            CoroutineScope(Dispatchers.IO).launch {
                val n = engine.clearAllIfw().getOrDefault(-1)
                Log.w("SdkPruner", "guard: recovery cleared $n IFW files")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = DisableEngine(this, io.github.deserthouse.prunoid.core.rules.RuleRepository(this))
        // 系统 受保护广播 → NOT_EXPORTED；应急清除需外部 adb 触发 → RECEIVER_EXPORTED
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            recoveryReceiver,
            IntentFilter(RecoveryReceiver.ACTION),
            Context.RECEIVER_EXPORTED
        )
        // API 34+ 规范：显式声明与 manifest 一致的 FGS 类型
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, io.github.deserthouse.prunoid.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Prunoid")
            .setContentText("规则守护运行中：应用更新后自动重应用已选规则")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(recoveryReceiver)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "rule_guard"
        const val NOTIFY_ID = 1
    }
}
