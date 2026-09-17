package io.github.deserthouse.prunoid

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
import kotlinx.coroutines.flow.first
import io.github.deserthouse.prunoid.core.engine.RuleGuardService
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.ui.AppDetailScreen
import io.github.deserthouse.prunoid.ui.AppListScreen
import io.github.deserthouse.prunoid.ui.AppViewModel
import io.github.deserthouse.prunoid.ui.SdkLibraryScreen
import io.github.deserthouse.prunoid.ui.SdkPrunerTheme
import io.github.deserthouse.prunoid.ui.SettingsScreen

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
        // 自动重应用开关（设置）：关=不启动规则守护（A15+ 收不到包事件，需手动重扫）
        val autoOn = kotlinx.coroutines.runBlocking {
            io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@MainActivity)
                .settings.first().autoReapply
        }
        if (autoOn) startForegroundService(Intent(this, RuleGuardService::class.java))
    }
}

// 三屏切换（列表/详情/设置），各自持有 Scaffold；外层只做选中状态管理
@Composable
fun SdkPrunerApp() {
    val vm: AppViewModel = viewModel()
    var selected by remember { mutableStateOf<ScannedApp?>(null) }
    var inSettings by remember { mutableStateOf(false) }
    var inLibrary by remember { mutableStateOf(false) }
    // 详情/设置/SDK 库屏系统返回 = 回上级，不退出 app
    BackHandler(enabled = selected != null || inSettings || inLibrary) {
        when {
            inSettings -> inSettings = false
            inLibrary -> inLibrary = false
            else -> selected = null
        }
    }
    when {
        inSettings -> SettingsScreen(vm, onBack = { inSettings = false })
        inLibrary -> SdkLibraryScreen(vm, onBack = { inLibrary = false })
        selected == null -> AppListScreen(
            vm,
            onOpen = { selected = it },
            onOpenSettings = { inSettings = true },
            onOpenLibrary = { inLibrary = true }
        )
        else -> AppDetailScreen(selected!!, vm, onBack = { selected = null })
    }
}
