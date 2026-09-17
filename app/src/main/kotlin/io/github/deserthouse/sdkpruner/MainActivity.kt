package io.github.deserthouse.sdkpruner

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkPrunerApp() {
    val vm: AppViewModel = viewModel()
    var selected by remember { mutableStateOf<ScannedApp?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "SDK-Pruner" else selected!!.label) },
                navigationIcon = {
                    if (selected != null) IconButton(onClick = { selected = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val s = selected
        val contentModifier = Modifier.padding(padding)
        if (s == null) {
            AppListScreen(vm, onOpen = { selected = it }, modifier = contentModifier)
        } else {
            AppDetailScreen(s, vm, onBack = { selected = null }, modifier = contentModifier)
        }
    }
}
