package io.github.deserthouse.sdkpruner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.sdkpruner.core.engine.AppliedRulesStore
import io.github.deserthouse.sdkpruner.core.engine.DisableEngine
import io.github.deserthouse.sdkpruner.core.engine.Engine
import io.github.deserthouse.sdkpruner.core.rules.RuleRepository
import io.github.deserthouse.sdkpruner.core.rules.Safety
import io.github.deserthouse.sdkpruner.core.rules.SettingsRepository
import io.github.deserthouse.sdkpruner.core.scanner.ScannedApp
import io.github.deserthouse.sdkpruner.core.scanner.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val scanning: Boolean = false,
    val apps: List<ScannedApp> = emptyList(),
    val showSystem: Boolean = false,
    val hitsOnly: Boolean = false,
    val rootGranted: Boolean = false,
    val message: String? = null,
    val engine: Engine = Engine.IFW,   // 双引擎切换，默认 IFW（app 无感知、无法自恢复）
    val busy: Boolean = false,         // 任一 root/网络操作进行中（按钮禁用 + 进度）
    val applied: Map<String, AppliedRulesStore.AppliedEntry> = emptyMap(), // 包名 -> 已应用记录
    val backupKeep: Int = 10           // 备份保留份数（设置页可调）
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val rules = RuleRepository(app)
    private val scanner = Scanner(app, rules)
    private val engine = DisableEngine(app, rules)
    private val applied = AppliedRulesStore(app)
    private val settings = SettingsRepository(app)

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state

    init {
        viewModelScope.launch {
            // 主动建立 root shell（首次调用触发 Magisk su 授权请求）
            val root = withContext(Dispatchers.IO) { engine.rootAvailable() }
            _state.update {
                it.copy(rootGranted = root, message = if (root) null else "未取得 root：扫描可用，禁用操作不可用")
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { st ->
                    st.copy(
                        backupKeep = s.backupKeep,
                        // 用户本次会话未手动切引擎时，跟随设置的默认引擎
                        engine = if (!userTouchedEngine) Engine.entries.first { it.name == s.defaultEngine } else st.engine
                    )
                }
            }
        }
        rescan()
    }

    private var userTouchedEngine = false

    fun rescan() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val apps = try {
                withContext(Dispatchers.IO) { scanner.scanAll() }
            } catch (e: Exception) {
                android.util.Log.e("SdkPruner", "scan failed", e)
                _state.update { it.copy(scanning = false, message = "扫描失败: ${e.message}") }
                return@launch
            }
            android.util.Log.d("SdkPruner", "scan done: ${apps.size} apps, ${apps.sumOf { it.matchedSdks.size }} hits")
            _state.update { it.copy(scanning = false, apps = apps, applied = withContext(Dispatchers.IO) { applied.all() }) }
        }
    }

    fun toggleShowSystem() {
        _state.update { it.copy(showSystem = !it.showSystem) }
    }

    fun toggleHitsOnly() {
        _state.update { it.copy(hitsOnly = !it.hitsOnly) }
    }

    fun selectEngine(e: Engine) {
        userTouchedEngine = true
        _state.update { it.copy(engine = e) }
    }

    // ── 设置（设置页读写） ────────────────────────────────────────
    fun setDefaultEngine(name: String) {
        viewModelScope.launch {
            settings.setDefaultEngine(name)
            _state.update { it.copy(engine = Engine.entries.first { e -> e.name == name }) }
        }
    }

    fun setBackupKeep(v: Int) {
        viewModelScope.launch { settings.setBackupKeep(v) }
    }

    fun subscriptionMeta() = rules.subscriptionMeta()

    /** 订阅规则源（OkHttp 拉取 + 缓存 + 合并重建）；成功后自动重扫 */
    fun subscribe(url: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { rules.subscribe(url) }
            _state.update { it.copy(busy = false) }
            onDone(result.message)
            if (result.ok) rescan()
        }
    }

    fun unsubscribe(onDone: (String) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { rules.unsubscribe() }
            onDone("已退订，恢复内置快照")
            rescan()
        }
    }

    fun subscriptionInfo(): Pair<String?, String?> = rules.subscriptionInfo()

    fun ruleSideEffect(ruleId: String): String? = rules.rule(ruleId)?.sideEffect

    fun ruleInfo(ruleId: String) = rules.rule(ruleId)

    // ── 备份与应急恢复 ────────────────────────────────────────────
    fun listBackups(): List<String> =
        runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { engine.listBackups() } }
            .getOrDefault(emptyList())

    fun restoreBackup(path: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val msg = withContext(Dispatchers.IO) {
                engine.restoreBackup(path).fold(
                    onSuccess = { "已恢复备份（IFW 即时生效；pm 状态重启后完全生效）" },
                    onFailure = { "恢复失败：${it.message}" }
                )
            }
            _state.update { it.copy(busy = false, applied = withContext(Dispatchers.IO) { applied.all() }) }
            onDone(msg)
        }
    }

    fun clearAllIfw(onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val msg = withContext(Dispatchers.IO) {
                engine.clearAllIfw().fold(
                    onSuccess = { "已清除 $it 个 IFW 规则文件" },
                    onFailure = { "清除失败：${it.message}" }
                )
            }
            _state.update { it.copy(busy = false, applied = withContext(Dispatchers.IO) { applied.all() }) }
            onDone(msg)
        }
    }

    fun visibleApps(apps: List<ScannedApp>, showSystem: Boolean): List<ScannedApp> =
        apps.filter { showSystem || !it.isSystem }

    private fun byTypeFor(scanned: ScannedApp, ruleIds: Set<String>): Map<String, List<String>> =
        scanned.matchedSdks
            .filter { it.ruleId in ruleIds && (it.safety == Safety.CAUTION || it.safety == Safety.SAFE) }
            .flatMap { it.componentTypes.entries }
            .groupBy({ it.value }, { it.key })

    /** 应用用户勾选的 SDK（默认勾选 SAFE/CAUTION；用户可显式加选 RISKY/UNKNOWN） */
    fun applyRules(scanned: ScannedApp, selectedRuleIds: Set<String>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val msg = withContext(Dispatchers.IO) {
                if (!engine.rootAvailable()) return@withContext "需要 root"
                if (DisableEngine.isForbidden(scanned.packageName)) return@withContext "系统 app 已被白名单拦截"
                val selected = scanned.matchedSdks.filter { it.ruleId in selectedRuleIds }
                val targets = selected.flatMap { it.matchedComponents }
                if (targets.isEmpty()) return@withContext "无可应用的组件目标"
                val backup = engine.backup(_state.value.backupKeep).getOrNull()
                val byType = byTypeFor(scanned, selectedRuleIds)
                val r = when (_state.value.engine) {
                    Engine.IFW -> engine.applyIfw(scanned.packageName, byType)
                    Engine.PM -> engine.applyPm(scanned.packageName, targets)
                }
                // 记录已应用目标，供 PACKAGE_REPLACED 后自动增量重应用
                if (r.isSuccess) {
                    applied.record(
                        scanned.packageName, _state.value.engine.name, targets,
                        byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap()
                    )
                }
                _state.update { it.copy(applied = applied.all()) }
                buildString {
                    append("${_state.value.engine.name} 规则 ${r.getOrDefault(0)} 条已应用")
                    if (backup != null) append("（已自动备份）")
                    r.exceptionOrNull()?.let { append(" — 失败：${it.message}") }
                }
            }
            _state.update { it.copy(busy = false) }
            onDone(msg)
        }
    }

    fun restoreApp(scanned: ScannedApp, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val msg = withContext(Dispatchers.IO) {
                val targets = scanned.matchedSdks
                    .filter { it.safety == Safety.CAUTION || it.safety == Safety.SAFE }
                    .flatMap { it.matchedComponents }
                val r1 = engine.removeIfw(scanned.packageName)
                val r2 = engine.enablePm(scanned.packageName, targets)
                applied.remove(scanned.packageName)
                _state.update { it.copy(applied = applied.all()) }
                val ok = r1.isSuccess && r2.isSuccess
                if (ok) "已恢复（IFW 规则移除 + pm 组件重启用）" else "失败：${r1.exceptionOrNull()?.message ?: r2.exceptionOrNull()?.message}"
            }
            _state.update { it.copy(busy = false) }
            onDone(msg)
        }
    }
}
