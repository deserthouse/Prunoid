package io.github.deserthouse.prunoid.ui

import io.github.deserthouse.prunoid.R
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
    val icons: Map<String, android.graphics.drawable.Drawable> = emptyMap(), // 扫描后后台预载，列表/详情零主线程 binder 调用
    // 批G：现场禁用集（包名→组件类名）。展示层以现场为准；applied 记账只喂恢复。
    val liveDisabled: Map<String, Set<String>> = emptyMap(),
    val ifwTotal: Int = 0,
    // 批H 彩蛋（对齐 OptIcon）：About 卡整体可点，🐾×7 解锁作者块（持久化）；碎碎念 🍆×6→💦 烧断
    val easterUnlocked: Boolean = false,
    val easterExpanded: Boolean = false,
    val easterRambleBurned: Boolean = false,
    val language: String = ""
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx get() = getApplication<Application>()
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
                it.copy(rootGranted = root, message = if (root) null else appCtx.getString(R.string.vm_no_root))
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
                        easterUnlocked = s.easterUnlocked,
                        easterRambleBurned = s.easterRambleBurned,
                        language = s.language,
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

    // ── 彩蛋（对齐 OptIcon）：tap 计数仅存内存，解锁持久化 ──
    private var aboutTapCount = 0
    private var rambleTapCount = 0

    fun onAboutCardTapped() {
        if (_state.value.easterUnlocked) {
            _state.update { it.copy(easterExpanded = !it.easterExpanded) }
            return
        }
        aboutTapCount++
        if (aboutTapCount < 7) {
            _state.update { it.copy(message = "🐾".repeat(aboutTapCount)) }
            return
        }
        aboutTapCount = 0
        viewModelScope.launch {
            settings.setEasterUnlocked(true)
            _state.update { it.copy(easterUnlocked = true, easterExpanded = true, message = "🐺") }
        }
    }

    /** 批I：手动语言（"" = 跟随系统）；落盘完成后 UI 再 recreate，避免竞态读旧值 */
    fun setLanguage(v: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            settings.setLanguage(v)
            onDone()
        }
    }

    /** 批I：跨应用统一禁用某 SDK——对规则库中命中该 SDK 的所有已安装应用写 IFW（组件取规则全量，覆盖未来更新） */
    fun disableSdkEverywhere(ruleId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val msg = withContext(Dispatchers.IO) {
                if (!engine.rootAvailable()) return@withContext appCtx.getString(R.string.vm_need_root)
                val rule = rules.rule(ruleId) ?: return@withContext appCtx.getString(R.string.vm_no_targets)
                val components = rule.components.map { it.`class` }
                val byType = rule.components.groupBy({ it.type }, { it.`class` })
                    .mapValues { it.value }
                    .filterKeys { it != "native" }
                if (byType.isEmpty()) return@withContext appCtx.getString(R.string.vm_no_targets)
                // 白名单（系统/框架）应用一律跳过——安全层语义，不做半写
                val targets = _state.value.apps
                    .filter { a -> a.matchedSdks.any { it.ruleId == ruleId } }
                    .filter { !DisableEngine.isForbidden(it.packageName) }
                if (targets.isEmpty()) return@withContext appCtx.getString(R.string.vm_no_targets)
                engine.backup(_state.value.backupKeep).getOrNull()
                var ok = 0
                val touched = mutableListOf<String>()
                targets.forEach { a ->
                    val r = engine.applyIfw(a.packageName, byType)
                    if (r.isSuccess) {
                        ok++
                        touched.add(a.packageName)
                        applied.record(
                            a.packageName, Engine.IFW.name, components,
                            byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap()
                        )
                    }
                }
                // 批G：批量写入后统一重读现场
                val live = engine.readLiveDisabled(touched).first
                _state.update {
                    it.copy(
                        applied = applied.all(),
                        liveDisabled = it.liveDisabled + live.mapValues { e -> e.value.toSet() }
                    )
                }
                appCtx.getString(R.string.vm_sdk_everywhere_ok, rule.name, ok, targets.size,
                    components.size)
            }
            _state.update { it.copy(busy = false) }
            onDone(msg)
        }
    }

    fun onRambleTapped() {
        if (_state.value.easterRambleBurned) {
            _state.update { it.copy(message = appCtx.getString(R.string.easter_dry)) }
            return
        }
        rambleTapCount++
        if (rambleTapCount < 7) {
            _state.update { it.copy(message = "🍆".repeat(rambleTapCount)) }
            return
        }
        rambleTapCount = 0
        viewModelScope.launch {
            settings.setEasterRambleBurned(true)
            _state.update { it.copy(easterRambleBurned = true, message = "💦") }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val apps = try {
                withContext(Dispatchers.IO) { scanner.scanAll() }
            } catch (e: Exception) {
                android.util.Log.e("SdkPruner", "scan failed", e)
                _state.update { it.copy(scanning = false, message = appCtx.getString(R.string.vm_scan_failed, e.message ?: "")) }
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
            // 批G：读现场——仅命中 app（shell 合并单次调用），IFW+pm 两处
            viewModelScope.launch(Dispatchers.IO) {
                val targets = apps.filter { it.matchedSdks.isNotEmpty() }.map { it.packageName }
                val (live, total) = engine.readLiveDisabled(targets)
                _state.update { it.copy(liveDisabled = live, ifwTotal = total) }
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
            if (source.builtin) { onDone(appCtx.getString(R.string.vm_source_builtin)); return@launch }
            withContext(Dispatchers.IO) { rules.clearSourceCache(source.id) }
            settings.setSources(_state.value.sources.filterNot { it.id == source.id })
            onDone(appCtx.getString(R.string.vm_source_removed, source.name))
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
                    onSuccess = { appCtx.getString(R.string.vm_restore_backup_ok) },
                    onFailure = { appCtx.getString(R.string.vm_restore_backup_fail, it.message ?: "") }
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
                    onSuccess = { appCtx.getString(R.string.vm_ifw_cleared, it) },
                    onFailure = { appCtx.getString(R.string.vm_clear_fail, it.message ?: "") }
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
                if (!engine.rootAvailable()) return@withContext appCtx.getString(R.string.vm_need_root)
                if (DisableEngine.isForbidden(scanned.packageName)) return@withContext appCtx.getString(R.string.vm_forbidden)
                val selected = scanned.matchedSdks.filter { it.ruleId in selectedRuleIds }
                val targets = selected.flatMap { it.matchedComponents }
                if (targets.isEmpty()) return@withContext appCtx.getString(R.string.vm_no_targets)
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
                // 批G：写入后单包重读现场（展示层以现场为准）
                val liveAfter = if (r.isSuccess) engine.readLiveDisabled(listOf(scanned.packageName)).first else null
                _state.update {
                    it.copy(
                        applied = applied.all(),
                        liveDisabled = liveAfter?.let { l -> it.liveDisabled + (scanned.packageName to (l[scanned.packageName] ?: emptySet())) } ?: it.liveDisabled
                    )
                }
                buildString {
                    append(appCtx.getString(R.string.vm_apply_ok, _state.value.engine.name, r.getOrDefault(0)))
                    if (backup != null) append(appCtx.getString(R.string.vm_apply_backup))
                    r.exceptionOrNull()?.let { append(appCtx.getString(R.string.vm_fail_suffix, it.message ?: "")) }
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
                // 批G：恢复后单包重读现场
                val liveAfterR = if (ok) engine.readLiveDisabled(listOf(scanned.packageName)).first else null
                _state.update {
                    it.copy(
                        liveDisabled = liveAfterR?.let { l -> it.liveDisabled + (scanned.packageName to (l[scanned.packageName] ?: emptySet())) } ?: it.liveDisabled
                    )
                }
                if (ok) appCtx.getString(R.string.vm_restore_ok) else appCtx.getString(R.string.vm_restore_fail, r1.exceptionOrNull()?.message ?: r2.exceptionOrNull()?.message ?: "")
            }
            _state.update { it.copy(busy = false) }
            onDone(msg)
        }
    }
}
