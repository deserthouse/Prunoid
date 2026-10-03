package io.github.deserthouse.prunoid.core.engine

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import io.github.deserthouse.prunoid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
            .setContentText(getString(R.string.guard_notif_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 批S1（N-1 热修）：无 root 冷启偶发 ForegroundServiceDidNotStartInTimeException——
        // 冷启窗口存在来历不明的 FGS 启动请求（早于 MainActivity.onCreate，三调用点均排除），
        // 与 ensureRuleGuard 的 stopService 竞态致服务未跑 startForeground 即被拆，AMS 仍按
        // 契约抛异常。三件套防御：
        // ① 先无条件履约 startForeground（onCreate 已调，此处兜底 restart 场景），掐死竞态窗口；
        // ② START_NOT_STICKY——sticky 重投递（intent=null）是神秘启动的头号候选，掐断源头；
        // ③ 配置自检——即使被误启动（realtime 档未开）也立刻自停，不留常驻通知。
        // 探针留档：下次再现时 intent 是否为 null 可直接定案归因（null=系统重投递）。
        Log.d("SdkPruner", "guard: onStartCommand intentNonNull=${intent != null} flags=$flags startId=$startId")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
        val on = runBlocking(Dispatchers.IO) {
            io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@RuleGuardService).settings.first()
                .let { it.autoReapply && it.workMode == "root" && it.reapplyMode == "realtime" }
        }
        if (!on) {
            Log.d("SdkPruner", "guard: config off, self-stopping")
            stopSelf()
        }
        // START_STICKY→NOT_STICKY：前台服务对 LMK 几乎免疫，重启覆盖面交给 BootReceiver/主界面兜底；
        // 而 sticky 重投递正是 N-1 神秘启动的头号嫌疑（重投递还附带 startForeground 义务，风险不对称）
        return START_NOT_STICKY
    }

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
