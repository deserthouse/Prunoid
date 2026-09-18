package io.github.deserthouse.prunoid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.rules.RuleRepository
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.engine.RuleGuardService
import io.github.deserthouse.prunoid.core.rules.SettingsRepository
import android.content.Intent
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
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
    val autoReapply: Boolean = true,   // 自动重应用总开关（控制 RuleGuardService）
    val sources: List<SettingsRepository.SubSource> = listOf(SettingsRepository.OFFICIAL_SOURCE),
    val applied: Map<String, AppliedRulesStore.AppliedEntry> = emptyMap(), // 包名 -> 已应用记录
    val backupKeep: Int = 10,          // 备份保留份数（设置页可调）
    val icons: Map<String, android.graphics.drawable.Drawable> = emptyMap() // 扫描后后台预载，列表/详情零主线程 binder 调用
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
            var lastSourceKey: String? = null
            settings.settings.collect { s ->
                // 源注册表变化 → 重建合并视图并重扫（规则集可能变化）
                val srcKey = s.sources.joinToString("|") { it.id + "@" + it.url }
                val sourcesChanged = lastSourceKey != null && srcKey != lastSourceKey
                val first = lastSourceKey == null
                lastSourceKey = srcKey
                rules.setSources(s.sources)
                if (sourcesChanged && !first) { /* 变更才重扫，见下 */ }
                _state.update { st ->
                    st.copy(
                        backupKeep = s.backupKeep,
                        autoReapply = s.autoReapply,
                        sources = s.sources,
                        // 用户本次会话未手动切引擎时，跟随设置的默认引擎
                        engine = if (!userTouchedEngine) Engine.entries.first { it.name == s.defaultEngine } else st.engine
                    )
                }
                if (sourcesChanged && !first) rescan()
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
            // 图标后台预载（getApplicationIcon 是 binder 调用，100+ app 不能放组合期主线程）
            viewModelScope.launch(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val icons = apps.associate { a ->
                    a.packageName to (runCatching { pm.getApplicationIcon(a.packageName) }.getOrNull())
                }.filterValues { it != null }.mapValues { it.value!! }
                _state.update { it.copy(icons = icons) }
            }
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

    /** 自动重应用总开关：关=停前台服务撤通知（A15+ 将收不到包事件），开=重启服务 */
    fun setAutoReapply(on: Boolean) {
        viewModelScope.launch {
            settings.setAutoReapply(on)
            val app = getApplication<Application>()
            if (on) {
                app.startForegroundService(Intent(app, RuleGuardService::class.java))
            } else {
                app.stopService(Intent(app, RuleGuardService::class.java))
            }
        }
    }

    // ── 多源订阅管理 ─────────────────────────────────────────────
    fun refreshSource(source: SettingsRepository.SubSource, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { rules.refreshSource(source.id, source.url) }
            _state.update { it.copy(busy = false) }
            if (result.ok) {
                settings.updateSourceFetched(source.id, java.time.Instant.now().toString())
                rescan()
            }
            onDone(result.message)
        }
    }

    fun addSource(name: String, url: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val id = "src_" + url.hashCode().let { if (it < 0) -it else it }
            val result = withContext(Dispatchers.IO) { rules.refreshSource(id, url) }
            if (result.ok) {
                val cur = _state.value.sources
                settings.setSources(cur + SettingsRepository.SubSource(
                    id = id, name = name.ifBlank { url.substringAfter("//").substringBefore('/') },
                    url = url, lastFetched = java.time.Instant.now().toString()
                ))
            }
            _state.update { it.copy(busy = false) }
            onDone(result.message)
        }
    }

    fun removeSource(source: SettingsRepository.SubSource, onDone: (String) -> Unit) {
        viewModelScope.launch {
            if (source.builtin) { onDone("官方源不可删除"); return@launch }
            withContext(Dispatchers.IO) { rules.clearSourceCache(source.id) }
            settings.setSources(_state.value.sources.filterNot { it.id == source.id })
            onDone("已移除源「${source.name}」")
        }
    }


    fun allRules() = rules.allRules()

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

    // 用户显式勾选的选择集就是最终意志（含显式加选的 RISKY/UNKNOWN），
    // 双引擎一致处理；安全建议由默认勾选与确认句承担，不在执行层二次过滤
    private fun byTypeFor(scanned: ScannedApp, ruleIds: Set<String>): Map<String, List<String>> =
        scanned.matchedSdks
            .filter { it.ruleId in ruleIds }
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
                // 按 applied 记录回滚（当时真实写入集），不用当前扫描重算——
                // 否则规则更新/显式加选的组件会成为恢复盲区
                val entry = applied.get(scanned.packageName)
                val r1 = engine.removeIfw(scanned.packageName)
                val r2 = entry?.components?.let { engine.enablePm(scanned.packageName, it) }
                    ?: Result.success(0)
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
