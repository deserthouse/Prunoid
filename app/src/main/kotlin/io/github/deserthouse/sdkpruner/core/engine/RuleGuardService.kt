package io.github.deserthouse.sdkpruner.core.engine

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log

// M3-R8：规则守护前台服务——保持进程活跃，使包安装/更新广播可投递
// （Android 15+ 对 cached 进程的 manifest receiver 强制跳过：Background execution not allowed）
// 动态注册 receiver 收 PACKAGE_ADDED/REPLACED，自动增量重应用已应用规则
class RuleGuardService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (AutoReapply.shouldHandle(context, pkg)) {
                Log.d("SdkPruner", "guard: package event $pkg")
                AutoReapply.process(context, pkg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        startForeground(NOTIFY_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, io.github.deserthouse.sdkpruner.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("SDK-Pruner")
            .setContentText("规则守护运行中：应用更新后自动重应用已选规则")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        return notification
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "rule_guard"
        const val NOTIFY_ID = 1
    }
}
