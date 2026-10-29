package io.github.deserthouse.prunoid

import androidx.compose.ui.res.stringResource
import io.github.deserthouse.prunoid.ui.SdkManageScreen
import io.github.deserthouse.prunoid.R
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
        // 自动重应用开关（设置）：关=不启动规则守护（A15+ 收不到包事件，需手动重扫）
        // 异步读 DataStore（不阻塞主线程冷启动）；启动前短暂空窗可接受——服务自身幂等
        lifecycleScope.launch {
            val repo = io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@MainActivity)
            val cfg = repo.settings.first()
            // F1：权限请求放 DataStore 读取之后——需要"用户点过 Don't allow"这个记忆
            // 才能决定弹不弹。弹窗本身触发 activity 重建→onCreate 再跑，无记忆=无限循环
            // （R10 F-P1 实锤：3s 一轮，用户唯一逃逸是系统设置手动授权）。
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !cfg.notifPermDenied
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            val guard = cfg.autoReapply && cfg.workMode == "root" && cfg.reapplyMode == "realtime"
            android.util.Log.d("SdkPruner", "ensureRuleGuard: guard=$guard mode=${cfg.workMode}/${cfg.reapplyMode}")
            if (guard) startForegroundService(Intent(this@MainActivity, RuleGuardService::class.java))
            else stopService(Intent(this@MainActivity, RuleGuardService::class.java))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        lifecycleScope.launch {
            // 授予→清标志；拒绝→置位永不再自动弹（设置页入口随时反悔）
            io.github.deserthouse.prunoid.core.rules.SettingsRepository(this@MainActivity)
                .setNotifPermDenied(grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }
}

/** 批P0-1：导航状态快照——Crossfade 退场期间持有旧状态，不再读活状态。
 *  批EE4（六审 B04）：selected 降为包名——普通 remember 持整个 ScannedApp 无生命周期契约，
 *  旋转/进程恢复丢详情位；对象从 VM 事实源按包名派生。
 *  批FF-B1③/B3（AA3）：新增 SDK 管理目的地（manageRuleId）。 */
private data class NavTarget(
    val inSettings: Boolean,
    val selectedPkg: String?,
    val manageRuleId: String?,
    val tab: Int
)

// 三屏切换（列表/详情/设置），各自持有 Scaffold；外层只做选中状态管理
@Composable
fun SdkPrunerApp() {
    val vm: AppViewModel = viewModel()
    // 批EE4（六审 B04）：路由键 rememberSaveable——配置变更/进程恢复后详情位保留
    var selectedPkg by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var inSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }   // 0=应用 1=SDK 库 2=统计
    // 批CC4：统计排行 → SDK 库联动；批M5（F04）：联动改传 ruleId 直达档案卡
    var pendingLibQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // 批FF4（七审 AV05）：列表查询/排序提升至根路由层——tab 往返与详情往返都保留
    //（分支内 rememberSaveable 会随 Crossfade 分支销毁而丢，只有顶层稳定）
    val listQuery = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val listSortName = androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(io.github.deserthouse.prunoid.ui.AppSort.DEFAULT.name)
    }
    // 批V4（ME-02）：列表滚动位同款提升——tab 往返保持（原 rememberLazyListState 在
    // tab 分支内，分支切换即销毁回顶；库页 libListState 已有先例）
    val listListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }
    // 批M5（F04）：库页浏览上下文同款外提——查询/作用域/排序/滚动位（028→030 丢失实证）
    val libQuery = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val libTab = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val libSortByName = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val libListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }
    // 批M5（F04）：管理页查询按 ruleId 隔离保存——管理往返不丢
    val manageQueries = androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    // 批FF-B1③/B3（AA3）：SDK 管理目的地路由（从库页档案卡入口进入）
    var manageRuleId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // 详情对象从 UiState 事实源按包名派生——apps 未加载（进程恢复早期）时为 null，自然回落列表页不崩
    val st = vm.state.collectAsState().value
    val selected = selectedPkg?.let { pkg -> st.apps.firstOrNull { it.packageName == pkg } }
    // 详情/设置/管理屏系统返回 = 回上级，不退出 app
    BackHandler(enabled = selectedPkg != null || inSettings || manageRuleId != null) {
        when {
            manageRuleId != null -> manageRuleId = null
            inSettings -> inSettings = false
            else -> selectedPkg = null
        }
    }
    // 批P0-1 修复：Crossfade target 持有导航键（退场期间按键派生旧对象渲染，不读可空活状态）。
    // 旧实现 target 存 selected?.packageName、内容读活状态 selected!!——返回动画期间
    // selected 已置 null 而旧内容仍被组合 → NPE 必崩（0.12.0/0.13.0 均受影响）
    androidx.compose.animation.Crossfade(targetState = NavTarget(inSettings, selectedPkg, manageRuleId, tab), label = "nav") { nav ->
        val navApp = nav.selectedPkg?.let { pkg -> st.apps.firstOrNull { it.packageName == pkg } }
        when {
            nav.inSettings -> SettingsScreen(vm, onBack = { inSettings = false })
            nav.manageRuleId != null -> SdkManageScreen(
                vm, nav.manageRuleId,
                query = manageQueries.value[nav.manageRuleId] ?: "",
                onQueryChange = { q -> if (nav.manageRuleId != null) manageQueries.value = manageQueries.value + (nav.manageRuleId to q) },
                onBack = { manageRuleId = null },
            )
            navApp != null -> AppDetailScreen(navApp, vm, onBack = { selectedPkg = null })
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
                        when (nav.tab) {
                            0 -> AppListScreen(
                                vm,
                                listStateState = listListState,
                                queryState = listQuery,
                                onQueryChange = { listQuery.value = it },
                                sortNameState = listSortName,
                                onSortNameChange = { listSortName.value = it },
                                onOpen = { selectedPkg = it.packageName },
                                onOpenSettings = { inSettings = true },
                                onOpenLibrary = { tab = 1 }
                            )
                            1 -> SdkLibraryScreen(
                                vm,
                                queryState = libQuery,
                                libTabState = libTab,
                                sortByNameState = libSortByName,
                                listState = libListState,
                                pendingRuleId = pendingLibQuery,
                                onConsumePendingRule = { pendingLibQuery = null },
                                onOpenManage = {
                                    manageQueries.value = manageQueries.value + (it to (manageQueries.value[it] ?: ""))
                                    manageRuleId = it
                                },
                            )
                            else -> StatsScreen(vm, onOpenSdk = { name ->
                                pendingLibQuery = name
                                tab = 1
                            })
                        }
                    }
            }
        }
    }
}
