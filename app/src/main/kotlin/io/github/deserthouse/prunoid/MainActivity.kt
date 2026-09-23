package io.github.deserthouse.prunoid

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.R
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
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
            // 批N：常驻守护仅 root+realtime 档启动（默认 open=启动对账，无常驻）
            val cfg = io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@MainActivity).settings.first()
            val guard = cfg.autoReapply && cfg.workMode == "root" && cfg.reapplyMode == "realtime"
            if (guard) startForegroundService(Intent(this@MainActivity, RuleGuardService::class.java))
            else stopService(Intent(this@MainActivity, RuleGuardService::class.java))
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
    androidx.compose.animation.Crossfade(targetState = Triple(inSettings, selected?.packageName, tab), label = "nav") { nav ->
        val (inSettingsNav, selPkg, tabNav) = nav
        when {
            inSettingsNav -> SettingsScreen(vm, onBack = { inSettings = false })
            selPkg != null -> AppDetailScreen(selected!!, vm, onBack = { selected = null })
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
                    when (tabNav) {
                        0 -> AppListScreen(
                            vm,
                            onOpen = { selected = it },
                            onOpenSettings = { inSettings = true },
                            onOpenLibrary = { tab = 1 }
                        )
                        1 -> SdkLibraryScreen(vm)
                        else -> StatsScreen(vm)
                    }
                }
            }
        }
    }
}
