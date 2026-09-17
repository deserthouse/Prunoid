package io.github.deserthouse.sdkpruner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deserthouse.sdkpruner.core.engine.Engine
import io.github.deserthouse.sdkpruner.core.rules.Safety
import io.github.deserthouse.sdkpruner.core.scanner.ScannedApp

// M1-R5：两屏结构（列表 → 详情）。M3E 视觉与图标加载在后续迭代补足。

@Composable
fun AppListScreen(vm: AppViewModel, onOpen: (ScannedApp) -> Unit, modifier: Modifier = Modifier) {
    val st by vm.state.collectAsState()
    var showSubscribe by remember { mutableStateOf(false) }
    var subMsg by remember { mutableStateOf<String?>(null) }
    val (subUrl, subAt) = remember(st) { vm.subscriptionInfo() }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = st.showSystem,
                onClick = { vm.toggleShowSystem() },
                label = { Text("含系统 app（只读）") }
            )
            Spacer(Modifier.weight(1f))
            Text(if (st.scanning) "扫描中…" else "${st.apps.filter { st.showSystem || !it.isSystem }.size} 个 app")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (subUrl != null) "已订阅：$subUrl" else "未订阅（使用内置快照）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            TextButton(onClick = { showSubscribe = true }) { Text(if (subUrl == null) "订阅源" else "更换") }
            if (subUrl != null) TextButton(onClick = { vm.unsubscribe { subMsg = it } }) { Text("退订") }
        }
        subMsg?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
        }
        st.message?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
        }
        if (showSubscribe) {
            SubscribeDialog(
                initial = subUrl.orEmpty(),
                onDismiss = { showSubscribe = false },
                onConfirm = { url ->
                    showSubscribe = false
                    vm.subscribe(url) { subMsg = it }
                }
            )
        }
        val apps = remember(st.apps, st.showSystem) { st.apps.filter { st.showSystem || !it.isSystem } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(apps, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label.ifEmpty { app.packageName }) },
                    supportingContent = { Text(app.packageName) },
                    trailingContent = {
                        if (app.matchedSdks.isNotEmpty()) AssistChip(
                            onClick = { onOpen(app) },
                            label = { Text("${app.matchedSdks.size} SDK") }
                        )
                    },
                    modifier = Modifier.clickable { onOpen(app) }
                )
            }
        }
    }
}

@Composable
fun SubscribeDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则订阅源") },
        text = {
            Column {
                Text("格式与内置快照一致（schemaVersion + sdks），支持自建源")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(url.trim()) },
                enabled = url.startsWith("http")
            ) { Text("订阅") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun AppDetailScreen(app: ScannedApp, vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var msg by remember { mutableStateOf<String?>(null) }
    val st by vm.state.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(app.label, style = MaterialTheme.typography.headlineSmall)
        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Engine.entries.forEach { e ->
                FilterChip(
                    selected = st.engine == e,
                    onClick = { vm.selectEngine(e) },
                    label = { Text(e.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { vm.applyRules(app) { msg = it } }) { Text("应用 CAUTION+ 规则") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.restoreApp(app) { msg = it } }) { Text("恢复") }
        }
        msg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(app.matchedSdks, key = { it.ruleId }) { hit ->
                ListItem(
                    headlineContent = { Text(hit.name) },
                    supportingContent = {
                        Column {
                            Text("${hit.category} · ${safetyLabel(hit.safety)}")
                            if (hit.matchedComponents.isNotEmpty()) {
                                Text("命中组件 ${hit.matchedComponents.size}：${hit.matchedComponents.take(2).joinToString()}")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = when (hit.safety) {
                            Safety.SAFE -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                )
                HorizontalDivider()
            }
        }
    }
}

fun safetyLabel(s: Safety) = when (s) {
    Safety.SAFE -> "可安全禁用"
    Safety.CAUTION -> "谨慎禁用"
    Safety.RISKY -> "禁用有风险"
    Safety.UNKNOWN -> "影响未知"
}
