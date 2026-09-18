package io.github.deserthouse.prunoid

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.deserthouse.prunoid.core.engine.RuleGuardService
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.deserthouse.prunoid.ui.AppDetailScreen
import io.github.deserthouse.prunoid.ui.AppListScreen
import io.github.deserthouse.prunoid.ui.AppViewModel
import io.github.deserthouse.prunoid.ui.SdkLibraryScreen
import io.github.deserthouse.prunoid.ui.SdkPrunerTheme
import io.github.deserthouse.prunoid.ui.SettingsScreen
import io.github.deserthouse.prunoid.ui.StatsScreen

class MainActivity : ComponentActivity() {
    // 批I：手动语言覆盖（"" = 跟随系统）。attachBaseContext 包裹 activity，同时刷应用级资源，
    // 使 VM 的 appCtx.getString 同步切换；DataStore 冷读一次性成本可接受。
    override fun attachBaseContext(newBase: android.content.Context) {
        val tag = kotlinx.coroutines.runBlocking {
            runCatching {
                io.github.deserthouse.prunoid.core.rules.SettingsRepository(newBase).settings.first().language
            }.getOrDefault("")
        }
        val base = if (tag.isBlank()) newBase else {
            val loc = java.util.Locale.forLanguageTag(tag)
            java.util.Locale.setDefault(loc)
            val cfg = android.content.res.Configuration(newBase.resources.configuration)
            cfg.setLocale(loc)
            newBase.createConfigurationContext(cfg)
        }
        super.attachBaseContext(base)
        // 应用级资源同步（VM 消息走 appCtx）
        if (!tag.isBlank()) {
            val appRes = applicationContext.resources
            val cfg = android.content.res.Configuration(appRes.configuration)
            cfg.setLocale(java.util.Locale.forLanguageTag(tag))
            appRes.updateConfiguration(cfg, appRes.displayMetrics)
        }
    }

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
            RuleGuardService.CHANNEL_ID, getString(R.string.guard_channel),
            android.app.NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // 自动重应用开关（设置）：关=不启动规则守护（A15+ 收不到包事件，需手动重扫）
        // 异步读 DataStore（不阻塞主线程冷启动）；启动前短暂空窗可接受——服务自身幂等
        lifecycleScope.launch {
            val autoOn = io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@MainActivity)
                .settings.first().autoReapply
            if (autoOn) startForegroundService(Intent(this@MainActivity, RuleGuardService::class.java))
        }
    }
}

// 三屏切换（列表/详情/设置），各自持有 Scaffold；外层只做选中状态管理
@Composable
fun SdkPrunerApp() {
    val vm: AppViewModel = viewModel()
    var selected by remember { mutableStateOf<ScannedApp?>(null) }
    var inSettings by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }   // 0=应用 1=SDK 库 2=统计
    // 详情/设置屏系统返回 = 回上级，不退出 app
    BackHandler(enabled = selected != null || inSettings) {
        if (inSettings) inSettings = false else selected = null
    }
    when {
        inSettings -> SettingsScreen(vm, onBack = { inSettings = false })
        selected != null -> AppDetailScreen(selected!!, vm, onBack = { selected = null })
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_apps)) }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.LibraryBooks, contentDescription = null) },
                        label = { Text(stringResource(R.string.menu_library)) }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_stats)) }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> AppListScreen(
                        vm,
                        onOpen = { selected = it },
                        onOpenSettings = { inSettings = true },
                        onOpenLibrary = { tab = 1 }
                    )
                    1 -> SdkLibraryScreen(vm, onBack = { tab = 0 })
                    else -> StatsScreen(vm)
                }
            }
        }
    }
}
