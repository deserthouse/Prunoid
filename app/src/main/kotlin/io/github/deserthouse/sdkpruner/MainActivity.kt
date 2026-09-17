package io.github.deserthouse.sdkpruner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deserthouse.sdkpruner.core.engine.RuleGuardService
import io.github.deserthouse.sdkpruner.core.scanner.ScannedApp
import io.github.deserthouse.sdkpruner.ui.AppDetailScreen
import io.github.deserthouse.sdkpruner.ui.AppListScreen
import io.github.deserthouse.sdkpruner.ui.AppViewModel
import io.github.deserthouse.sdkpruner.ui.SdkPrunerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureRuleGuard()
        setContent {
            SdkPrunerTheme {
                SdkPrunerApp()
            }
        }
    }

    private fun ensureRuleGuard() {
        // M3-R8：规则守护前台服务（保持进程活跃以接收包更新广播）
        val channel = android.app.NotificationChannel(
            RuleGuardService.CHANNEL_ID, "规则守护",
            android.app.NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, RuleGuardService::class.java))
    }
}

// 两屏各自持有 Scaffold（大标题/底部操作区），外层只做选中状态切换
@Composable
fun SdkPrunerApp() {
    val vm: AppViewModel = viewModel()
    var selected by remember { mutableStateOf<ScannedApp?>(null) }
    // 详情屏系统返回 = 回列表，不退出 app
    BackHandler(enabled = selected != null) { selected = null }
    val s = selected
    if (s == null) {
        AppListScreen(vm, onOpen = { selected = it })
    } else {
        AppDetailScreen(s, vm, onBack = { selected = null })
    }
}
