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
import kotlinx.serialization.json.*
import kotlinx.coroutines.withContext

data class AppUiState(
    val scanning: Boolean = false,
    val apps: List<ScannedApp> = emptyList(),
    val showSystem: Boolean = false,
    val hitsOnly: Boolean = false,
    val rootGranted: Boolean = false,
    val message: String? = null,
    val engine: Engine = Engine.IFW,   // 双引擎切换，默认 IFW（app 无感知、无法自恢复）
    val busyOp: String? = null,        // 进行中的操作名；busy 为派生值（批H：操作级状态）
    val msgSeq: Int = 0,               // 消息序号（同文本连发也能弹 Snackbar）
    val guided: Boolean = false,       // 首启引导已读
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
    // 批N：工作方式与档位（audit=只读审计；reapply open=启动对账/realtime=常驻）
    val workMode: io.github.deserthouse.prunoid.core.engine.WorkModeInfo =
        io.github.deserthouse.prunoid.core.engine.WorkModeInfo.ROOT,
    val reapplyMode: String = "open",
    val backupEnabled: Boolean = false,
    // 批T8：列表筛选条件统一入 VM（全部会话态，导航往返保留、冷启复位——与 hitsOnly/showSystem 同层）
    val listCatSel: Set<String> = emptySet(),
    val listSafetySel: Safety? = null,
    val listAppliedOnly: Boolean = false,
    // 批T2：内置快照可溯源（"2026-09-21 · 2003"）
    val snapshotMeta: String = ""
) {
    // 批H：busy 由 busyOp 派生（UI 读法不变）
    val busy: Boolean get() = busyOp != null
}

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
                it.copy(rootGranted = root)
            }
            if (!root) setMessage(appCtx.getString(R.string.vm_no_root))
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
                        workMode = io.github.deserthouse.prunoid.core.engine.WorkModeInfo.fromTag(s.workMode),
                        reapplyMode = s.reapplyMode,
                        backupEnabled = s.backupEnabled,
                        guided = s.guided,
                        sources = s.sources,
                        // 用户本次会话未手动切引擎时，跟随设置的默认引擎
                        engine = if (!userTouchedEngine) Engine.entries.first { it.name == s.defaultEngine } else st.engine
                    )
                }
                if (sourcesChanged && !first) rescan()
            }
        }
        rescan()
        // 批N：open 档启动对账——applied 记录 vs IFW 现场，缺口提示
        viewModelScope.launch {
            settings.settings.collect { s ->
                if (s.reapplyMode == "open" && s.workMode == "root") {
                    kotlinx.coroutines.delay(2500)
                    reconcileApplied()
                }
            }
        }
    }

    /** 对账：applied 记录组件 vs 现场禁用集，缺口 = 未覆盖组件数 */
    private suspend fun reconcileApplied() {
        val st = _state.value
        if (!st.rootGranted || st.apps.isEmpty() || st.liveDisabled.isEmpty()) return
        val live = st.liveDisabled
        val gap = st.applied.entries.sumOf { (pkg, e) ->
            val liveSet = live[pkg] ?: return@sumOf 0
            e.components.count { c -> c !in liveSet && c.substringAfterLast('/') !in liveSet }
        }
        if (gap > 0) {
            setMessage(appCtx.getString(R.string.vm_reconcile_gap, gap))
        }
    }

    private var userTouchedEngine = false

    /** 批H：带序号消息（同文本连发也能弹 Snackbar） */
    private fun setMessage(text: String) {
        _state.update { it.copy(message = text, msgSeq = it.msgSeq + 1) }
    }

    /** 批H：操作级 busy 包装——finally 保底清零（异常不再永久卡死全 UI） */
    private suspend fun <T> withBusy(op: String, block: suspend () -> T): T =
        try {
            _state.update { it.copy(busyOp = op) }
            withContext(Dispatchers.IO) { block() }
        } finally {
            _state.update { it.copy(busyOp = null) }
        }

    /** 批N：root 类操作统一闸门——非 ROOT 模式一律拒绝（UI 层已禁灰，此处安全兜底） */
    private fun rootGate(): Boolean =
        _state.value.workMode.id == io.github.deserthouse.prunoid.core.engine.WorkModeId.ROOT &&
            _state.value.rootGranted

    fun setWorkMode(tag: String) { viewModelScope.launch { settings.setWorkMode(tag) } }
    fun setReapplyMode(tag: String) { viewModelScope.launch { settings.setReapplyMode(tag) } }
    fun setBackupEnabled(on: Boolean) { viewModelScope.launch { settings.setBackupEnabled(on) } }

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
            setMessage("🐾".repeat(aboutTapCount))
            return
        }
        aboutTapCount = 0
        viewModelScope.launch {
            settings.setEasterUnlocked(true)
            _state.update { it.copy(easterUnlocked = true, easterExpanded = true, message = "🐺") }
        }
    }

    /** 批I：手动语言（"" = 跟随系统）；落盘完成后 UI 再 recreate，避免竞态读旧值 */
    /** 批I：跨应用统一禁用某 SDK——对规则库中命中该 SDK 的所有已安装应用写 IFW（组件取规则全量，覆盖未来更新） */
    fun disableSdkEverywhere(ruleId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("everywhere") {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                val rule = rules.rule(ruleId) ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                // 批库链修复①：目标组件改用各 app 扫描命中的真实组件
                // （规则锚点 87% 为空，旧实现必然 "no targets"）
                // 白名单（系统/框架）应用一律跳过——安全层语义，不做半写
                val targets = _state.value.apps
                    .filter { a -> a.matchedSdks.any { it.ruleId == ruleId } }
                    .filter { !DisableEngine.isForbidden(it.packageName) }
                if (targets.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                var ok = 0
                var total = 0
                val touched = mutableListOf<String>()
                targets.forEach { a ->
                    val hit = a.matchedSdks.first { it.ruleId == ruleId }
                    val byType = hit.componentTypes.entries
                        .groupBy({ it.value }, { it.key })
                        .filterKeys { it != "native" }
                    if (byType.isEmpty()) return@forEach
                    total++
                    val prev = applied.get(a.packageName)
                    val r = engine.applyIfw(a.packageName, byType)
                    if (r.isSuccess) {
                        ok++
                        touched.add(a.packageName)
                        // 合并记录：不覆盖该 app 其他 SDK 的条目
                        applied.record(
                            a.packageName, Engine.IFW.name,
                            (prev?.components.orEmpty() + hit.matchedComponents).distinct(),
                            (prev?.types.orEmpty() + hit.componentTypes)
                        )
                    }
                }
                if (total == 0) return@withBusy appCtx.getString(R.string.vm_no_targets)
                // 批G：批量写入后统一重读现场
                val live = engine.readLiveDisabled(touched).first
                _state.update {
                    it.copy(
                        applied = applied.all(),
                        liveDisabled = it.liveDisabled + live.mapValues { e -> e.value.toSet() }
                    )
                }
                appCtx.getString(R.string.vm_sdk_everywhere_ok, rule.name, ok, targets.size, total)
            }
            onDone(msg)
        }
    }

    /** 批库链修复③：per-app × 单 SDK 粒度开关（库页档案卡行开关专用） */
    fun setSdkForApp(pkg: String, ruleId: String, on: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy(if (on) "sdk-on" else "sdk-off") {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                val app = _state.value.apps.firstOrNull { it.packageName == pkg }
                    ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                val hit = app.matchedSdks.firstOrNull { it.ruleId == ruleId }
                    ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                val prev = applied.get(pkg)
                if (on) {
                    val byType: Map<String, List<String>> = hit.componentTypes.entries
                        .groupBy({ it.value }, { it.key })
                        .filterKeys { it != "provider" }
                    if (byType.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                    val r = engine.applyIfw(pkg, byType)
                    if (r.isSuccess) applied.record(
                        pkg, Engine.IFW.name,
                        (prev?.components.orEmpty() + hit.matchedComponents).distinct(),
                        (prev?.types.orEmpty() + hit.componentTypes)
                    )
                } else {
                    // off：仅移除该 SDK 命中的组件（IFW 定向移除 + pm enable）；
                    // 外部/其他来源 filter 幸存（removeIfw 语义批J#1 已修正）
                    val removed: Set<String> = hit.matchedComponents.toSet()
                    engine.enablePm(pkg, removed.toList())
                    engine.removeIfw(pkg, removeComponents = removed)
                    if (prev != null) applied.record(
                        pkg, prev.engine,
                        prev.components.filter { it !in removed },
                        prev.types.filterKeys { it !in removed }
                    ) else applied.remove(pkg)
                }
                val liveAfter = engine.readLiveDisabled(listOf(pkg)).first
                _state.update {
                    it.copy(liveDisabled = liveAfter.let { l -> it.liveDisabled + (pkg to (l[pkg] ?: emptySet())) })
                }
                ""
            }
            if (msg.isNotBlank()) onDone(msg)
        }
    }

    /** 档案卡开关状态：该 app 现场禁用集与该 SDK 命中组件有交集 = on */
    fun sdkEnabledFor(app: ScannedApp, ruleId: String): Boolean {
        val hit = app.matchedSdks.firstOrNull { it.ruleId == ruleId } ?: return false
        val live = _state.value.liveDisabled[app.packageName] ?: return false
        return hit.matchedComponents.any { c -> live.any { it == c || it == app.packageName + "/" + c } }
    }

    /** 批L4：禁用勾选的未识别组件（IFW 按类型分组；记 applied 供恢复） */
    fun disableUnmatched(pkg: String, byType: Map<String, List<String>>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("unmatched") {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                if (DisableEngine.isForbidden(pkg)) return@withBusy appCtx.getString(R.string.vm_forbidden)
                val comps = byType.values.flatten()
                if (comps.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                val r = engine.applyIfw(pkg, byType)
                if (r.isSuccess) {
                    // 批E1：合并记录——不覆盖该 app 其他 SDK 的 applied 条目
                    val prev = applied.get(pkg)
                    applied.record(
                        pkg, Engine.IFW.name,
                        (prev?.components.orEmpty() + comps).distinct(),
                        (prev?.types.orEmpty() + byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap())
                    )
                }
                val liveAfter = if (r.isSuccess) engine.readLiveDisabled(listOf(pkg)).first else null
                _state.update {
                    it.copy(
                        applied = applied.all(),
                        liveDisabled = liveAfter?.let { l -> it.liveDisabled + (pkg to (l[pkg] ?: emptySet())) } ?: it.liveDisabled
                    )
                }
                appCtx.getString(R.string.vm_apply_ok, "IFW", r.getOrDefault(0))
            }
            onDone(msg)
        }
    }

    /** 批O：导出某 app 的组件清单 JSON（供规则仓研判；纯本地分享，零上传）。批V3：serialization 构造替代手写拼接 */
    fun exportComponentReport(pkg: String): String {
        val app = _state.value.apps.firstOrNull { it.packageName == pkg } ?: return ""
        val obj = buildJsonObject {
            put("app", app.label)
            put("package", app.packageName)
            put("reportedAt", java.time.Instant.now().toString())
            put("matched", buildJsonArray {
                app.matchedSdks.forEach { h ->
                    add(buildJsonObject {
                        put("rule", h.name)
                        put("components", JsonArray(h.matchedComponents.map { JsonPrimitive(it) }))
                    })
                }
            })
            put("unmatched", buildJsonArray {
                app.unmatched.forEach { g ->
                    add(buildJsonObject {
                        put("prefix", g.prefix)
                        put("count", g.count)
                        put("components", JsonArray(g.components.take(50).map { JsonPrimitive(it) }))
                    })
                }
            })
        }
        return obj.toString()
    }


    // ── 批P：声明式（hook 模式）——SDK 画圈写 declarations.json ──
    val declared: Set<String> get() = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes
    val declarationsEnabled: Boolean get() = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.enabled

    init {
        viewModelScope.launch(Dispatchers.IO) {
            io.github.deserthouse.prunoid.core.engine.DeclarationsStore.read()
        }
    }

    /** 画圈/取消一个 SDK（ruleId → 其全部 packPrefixes 展开写声明文件） */
    fun toggleDeclaration(ruleId: String, on: Boolean, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val rule = rules.rule(ruleId) ?: return@launch
            val cur = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes
            val next = if (on) cur + rule.packPrefixes.map { it.trimEnd('.') }
                       else cur - rule.packPrefixes.map { it.trimEnd('.') }.toSet()
            val r = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.write(
                io.github.deserthouse.prunoid.core.engine.DeclarationsStore.Decl(
                    enabled = next.isNotEmpty(),
                    prefixes = next.sorted()
                )
            )
            onDone(if (r.isSuccess) "" else (r.exceptionOrNull()?.message ?: "write failed"))
        }
    }

    fun setDeclarationsEnabled(on: Boolean) {
        viewModelScope.launch {
            io.github.deserthouse.prunoid.core.engine.DeclarationsStore.write(
                io.github.deserthouse.prunoid.core.engine.DeclarationsStore.Decl(
                    enabled = on,
                    prefixes = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes.sorted()
                )
            )
        }
    }

    fun onRambleTapped() {
        if (_state.value.easterRambleBurned) {
            setMessage(appCtx.getString(R.string.easter_dry))
            return
        }
        rambleTapCount++
        if (rambleTapCount < 7) {
            setMessage("🍆".repeat(rambleTapCount))
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
                withContext(Dispatchers.IO) {
                    val out = scanner.scanAll()
                    val snap = runCatching { rules.snapshot }.getOrNull()
                    _state.update {
                        it.copy(snapshotMeta = snap?.generatedAt?.take(10)?.orEmpty().let { d ->
                            if (d != null && snap != null) "$d · ${snap.sdks.size}" else ""
                        })
                    }
                    out
                }
            } catch (e: Exception) {
                android.util.Log.e("SdkPruner", "scan failed", e)
                _state.update { it.copy(scanning = false) }
                setMessage(appCtx.getString(R.string.vm_scan_failed, e.message ?: ""))
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

    // 批T8：列表筛选三件套的 VM 入口
    fun toggleListCat(c: String) {
        _state.update { it.copy(listCatSel = if (c in it.listCatSel) it.listCatSel - c else it.listCatSel + c) }
    }

    fun toggleListSafety(s: Safety) {
        _state.update { it.copy(listSafetySel = if (it.listSafetySel == s) null else s) }
    }

    fun toggleListAppliedOnly() {
        _state.update { it.copy(listAppliedOnly = !it.listAppliedOnly) }
    }

    fun dismissGuide() {
        viewModelScope.launch { settings.setGuided() }
        _state.update { it.copy(guided = true) }
    }

    /** 库页搜索别名（批库链修复⑤）：规则规范名之外的组内别名文本 */
    fun aliasTextFor(name: String): String =
        rules.nameAliases[name]?.joinToString(" ") ?: ""

    /** 档案卡别名展示（批#11） */
    fun aliasesFor(name: String): String =
        rules.nameAliases[name]?.joinToString(" / ") ?: ""

    fun clearListFilters() {
        _state.update { it.copy(listCatSel = emptySet(), listSafetySel = null, listAppliedOnly = false, hitsOnly = false, showSystem = false) }
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
            val result = withBusy("refresh") { rules.refreshSource(source.id, source.url) }
            if (result.ok) {
                settings.updateSourceFetched(source.id, java.time.Instant.now().toString())
                rescan()
            }
            onDone(result.message)
        }
    }

    fun addSource(name: String, url: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = "src_" + url.hashCode().let { if (it < 0) -it else it }
            val result = withBusy("addsource") { rules.refreshSource(id, url) }
            if (result.ok) {
                val cur = _state.value.sources
                settings.setSources(cur + SettingsRepository.SubSource(
                    id = id, name = name.ifBlank { url.substringAfter("//").substringBefore('/') },
                    url = url, lastFetched = java.time.Instant.now().toString()
                ))
            }
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
            var ok = false
            val msg = withBusy("backup") {
                engine.restoreBackup(path).fold(
                    onSuccess = {
                        ok = true
                        appCtx.getString(R.string.vm_restore_backup_ok)
                    },
                    onFailure = { appCtx.getString(R.string.vm_restore_backup_fail, it.message ?: "") }
                )
            }
            _state.update { it.copy(applied = withContext(Dispatchers.IO) { applied.all() }) }
            if (ok) rescan()  // 批P2#30：恢复后现场与记录可能漂移，重扫校准
            onDone(msg)
        }
    }

    fun clearAllIfw(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("clearifw") {
                engine.clearAllIfw().fold(
                    onSuccess = { appCtx.getString(R.string.vm_ifw_cleared, it) },
                    onFailure = { appCtx.getString(R.string.vm_clear_fail, it.message ?: "") }
                )
            }
            _state.update { it.copy(applied = withContext(Dispatchers.IO) { applied.all() }) }
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
            val msg = withBusy("apply") {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                if (DisableEngine.isForbidden(scanned.packageName)) return@withBusy appCtx.getString(R.string.vm_forbidden)
                val selected = scanned.matchedSdks.filter { it.ruleId in selectedRuleIds }
                val targets = selected.flatMap { it.matchedComponents }
                if (targets.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                val backup = if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                val byType = byTypeForVM(scanned, selectedRuleIds)
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
            onDone(msg)
        }
    }

    fun restoreApp(scanned: ScannedApp, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("restore") {
                // 按 applied 记录回滚（当时真实写入集），不用当前扫描重算——
                // 否则规则更新/显式加选的组件会成为恢复盲区
                val entry = applied.get(scanned.packageName)
                // 批P0#8：保留删除——只移除本工具 applied 的 filter，外部/其他来源 IFW 规则幸存
                val r1 = engine.removeIfw(scanned.packageName, removeComponents = entry?.components?.toSet())
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
            onDone(msg)
        }
    }
}

/** 批债33：app 勾选 → 引擎类型分组（纯函数，供 VM 与单测共用） */
fun byTypeForVM(scanned: ScannedApp, ruleIds: Set<String>): Map<String, List<String>> =
    scanned.matchedSdks
        .filter { it.ruleId in ruleIds }
        .flatMap { it.componentTypes.entries }
        .groupBy({ it.value }, { it.key })
