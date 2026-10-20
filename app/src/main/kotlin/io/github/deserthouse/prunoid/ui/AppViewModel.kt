package io.github.deserthouse.prunoid.ui

import io.github.deserthouse.prunoid.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.ui.operations.OperationCenter
import io.github.deserthouse.prunoid.ui.operations.OperationItem
import io.github.deserthouse.prunoid.ui.operations.OperationKind
import io.github.deserthouse.prunoid.ui.operations.SelectionDraft
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.engine.OpCoordinator
import io.github.deserthouse.prunoid.core.rules.RuleRepository
import io.github.deserthouse.prunoid.core.rules.RuleSubscription
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.engine.RuleGuardService
import io.github.deserthouse.prunoid.core.rules.SettingsRepository
import android.content.Intent
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.coroutines.withContext

/** 列表筛选族（批G2c 自 AppUiState 平铺字段收拢）：全部会话态，导航往返保留、冷启复位 */
data class ListFilters(
    val hitsOnly: Boolean = false,
    val showSystem: Boolean = false,
    val catSel: Set<String> = emptySet(),
    val safety: Safety? = null,
    val appliedOnly: Boolean = false
)

/** About 彩蛋（批G2c；批W 精简）：🐾×7 解锁 → 作者块展开（头像/名字/GitHub 链接） */
data class EasterState(
    val unlocked: Boolean = false,
    val expanded: Boolean = false
)

data class AppUiState(
    val scanning: Boolean = false,
    val apps: List<ScannedApp> = emptyList(),
    val rootGranted: Boolean = false,
    val message: String? = null,
    val engine: Engine = Engine.IFW,   // 详情页当前引擎（可临时切换，不回写默认）
    val defaultEngine: Engine = Engine.IFW, // 批X4（审查 PN-16）：设置页显示的持久默认引擎
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
    // 批BB RV-09：账本损坏标志——恢复区显示重置入口；写操作在 VM 边界拒绝
    val ledgerCorrupted: Boolean = false,
    // 批H 彩蛋（对齐 OptIcon）：About 卡整体可点，🐾×7 解锁作者块（持久化）；碎碎念 🍆×6→💦 烧断
    val easter: EasterState = EasterState(),
    // 批N：工作方式与档位（audit=只读审计；reapply open=启动对账/realtime=常驻）
    val workMode: io.github.deserthouse.prunoid.core.engine.WorkModeInfo =
        io.github.deserthouse.prunoid.core.engine.WorkModeInfo.ROOT,
    val reapplyMode: String = "open",
    val backupEnabled: Boolean = false,
    // 批G2c：列表筛选族（批T8 会话态 + hitsOnly/showSystem 收拢）
    val filters: ListFilters = ListFilters(),
    // 批T2：内置快照可溯源（"2026-09-21 · 2003"）
    val snapshotMeta: String = "",
    // 批R4（审计 P-7）：声明式画圈状态入 UiState——原 VM 静态存储 getter 不触发重组，档案卡开关切换后不回显
    val declared: Set<String> = emptySet(),
    val declarationsEnabled: Boolean = false
) {
    // 批H：busy 由 busyOp 派生（UI 读法不变）
    val busy: Boolean get() = busyOp != null
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx get() = getApplication<Application>()
    private val rules = RuleRepository(app)
    private var scanner = Scanner(appCtx, rules.published)
    private val engine = DisableEngine(app, rules)
    // 批FF-B1①（AA7/方案B S1）：操作结果中心——逐项成败记录，B2/B3 的结果卡与管理面消费
    val opCenter = OperationCenter()
    // 批FF-B1②（方案B S1）：选择草稿——绑定规则代次，刷新后标 stale 提示重审（不自动改选）
    private val _draft = MutableStateFlow<SelectionDraft?>(null)
    val draft: StateFlow<SelectionDraft?> = _draft.asStateFlow()
    val rulesGeneration: Long get() = rules.generation

    fun syncDraft(pkg: String, ruleIds: Set<String>, engine: Engine) {
        val cur = _draft.value
        _draft.value = when {
            ruleIds.isEmpty() -> null
            cur != null && cur.packageName == pkg && cur.ruleIds == ruleIds && cur.engine == engine -> cur
            else -> SelectionDraft(pkg, ruleIds, engine, rules.generation)
        }
    }
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
            // 批R5（审计 B-4）：无 root 提示只走列表页常驻横幅（原来横幅+Snackbar 同文案双份且每次冷启弹）
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
                        easter = EasterState(unlocked = s.easterUnlocked),
                        workMode = io.github.deserthouse.prunoid.core.engine.WorkModeInfo.fromTag(s.workMode),
                        reapplyMode = s.reapplyMode,
                        backupEnabled = s.backupEnabled,
                        guided = s.guided,
                        sources = s.sources,
                        // 用户本次会话未手动切引擎时，跟随设置的默认引擎
                        engine = if (!userTouchedEngine) Engine.entries.first { it.name == s.defaultEngine } else st.engine,
                        defaultEngine = Engine.entries.first { it.name == s.defaultEngine }
                    )
                }
                if (sourcesChanged && !first) rescan()
                // 批X3（审查 R10/PN-02）：三元组变化即刻驱动守护服务生命周期——
                // 原实现只在 Activity onCreate 判定一次，模式切换后旧服务/旧 receiver 不受约束
                syncGuardService(s.autoReapply && s.workMode == "root" && s.reapplyMode == "realtime")
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
    private suspend fun <T> withBusy(op: String, locked: Boolean = false, block: suspend () -> T): T =
        try {
            _state.update { it.copy(busyOp = op) }
            if (locked) {
                // 批Z2（架构评审 A4）：写操作走 OpCoordinator 全局写锁——与 AutoReapply/
                // 声明写入共锁串行；locked=false 的读类操作（订阅刷新）不占锁
                OpCoordinator.write { withContext(Dispatchers.IO) { block() } }
            } else {
                withContext(Dispatchers.IO) { block() }
            }
        } finally {
            _state.update { it.copy(busyOp = null) }
        }

    /** 批N：root 类操作统一闸门——非 ROOT 模式一律拒绝（UI 层已禁灰，此处安全兜底） */
    private fun rootGate(): Boolean =
        _state.value.workMode.id == io.github.deserthouse.prunoid.core.engine.WorkModeId.ROOT &&
            _state.value.rootGranted

    /** 批BB RV-09：账本损坏时拒绝普通写（覆盖即销毁恢复依据）；恢复区提供显式重置 */
    private fun ledgerCorrupted(): Boolean = applied.corrupted

    fun setWorkMode(tag: String) { viewModelScope.launch { settings.setWorkMode(tag) } }
    fun setReapplyMode(tag: String) { viewModelScope.launch { settings.setReapplyMode(tag) } }
    fun setBackupEnabled(on: Boolean) { viewModelScope.launch { settings.setBackupEnabled(on) } }

    /** 批X3（审查 R10/PN-02）：守护服务与三元组同步（幂等——重复 start/startForegroundService 无害） */
    private var guardRunning: Boolean? = null
    private fun syncGuardService(shouldRun: Boolean) {
        if (guardRunning == shouldRun) return
        guardRunning = shouldRun
        val app = getApplication<Application>()
        if (shouldRun) {
            app.startForegroundService(Intent(app, RuleGuardService::class.java))
        } else {
            app.stopService(Intent(app, RuleGuardService::class.java))
        }
    }

    // ── 彩蛋（对齐 OptIcon）：tap 计数仅存内存，解锁持久化 ──
    private var aboutTapCount = 0

    fun onAboutCardTapped() {
        if (_state.value.easter.unlocked) {
            _state.update { it.copy(easter = it.easter.copy(expanded = !it.easter.expanded)) }
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
            _state.update { it.copy(easter = it.easter.copy(unlocked = true, expanded = true), message = "🐺") }
        }
    }

    /** 批I：手动语言（"" = 跟随系统）；落盘完成后 UI 再 recreate，避免竞态读旧值 */
    /** 批I：跨应用统一禁用某 SDK——对规则库中命中该 SDK 的所有已安装应用写 IFW（组件取规则全量，覆盖未来更新） */
    fun disableSdkEverywhere(ruleId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("everywhere", locked = true) {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                if (ledgerCorrupted()) return@withBusy appCtx.getString(R.string.vm_ledger_corrupted)
                val rule = rules.rule(ruleId) ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                // 批库链修复①：目标组件改用各 app 扫描命中的真实组件
                // （规则锚点 87% 为空，旧实现必然 "no targets"）
                // 白名单（系统/框架）应用一律跳过——安全层语义，不做半写
                val targets = _state.value.apps
                    .filter { a -> a.matchedSdks.any { it.ruleId == ruleId } }
                    .filter { !DisableEngine.isForbidden(it.packageName) && !it.isSystem }
                if (targets.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                var ok = 0
                var total = 0
                // 批FF3（七审 AV03）：回执组件数=各应用实际写入的唯一组件之和（不再冒用应用计数）
                var compWritten = 0
                val touched = mutableListOf<String>()
                targets.forEach { a ->
                    val hit = a.matchedSdks.first { it.ruleId == ruleId }
                    // 批Y2（架构评审 C3）：IFW 只写它支持的类型——native/provider 在计划层剔除，
                    // 执行/记账/回执同源（原记账 hit.matchedComponents 含 provider，IFW 文件少于账本）
                    val byType = hit.componentTypes.entries
                        .groupBy({ it.value }, { it.key })
                        .filterKeys { it != "native" && it != "provider" }
                    if (byType.isEmpty()) return@forEach
                    total++
                    val prev = applied.get(a.packageName)
                    val r = engine.applyIfw(a.packageName, byType)
                    if (r.isSuccess) {
                        ok++
                        compWritten += byType.values.flatten().distinct().size
                        touched.add(a.packageName)
                        // 批X2（审查 R04）：合并语义走 recordMerge，目标以 ruleIds 记账
                        applied.recordMerge(
                            a.packageName, Engine.IFW.name,
                            byType.values.flatten(),
                            byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap(),
                            addedRuleIds = setOf(ruleId)
                        )
                    }
                }
                if (total == 0) return@withBusy appCtx.getString(R.string.vm_no_targets)
                // 批G：批量写入后统一重读现场
                val live = engine.readLiveDisabled(touched).first
                _state.update {
                    it.copy(
                        applied = applied.all(),
                            ledgerCorrupted = applied.corrupted,
                        liveDisabled = it.liveDisabled + live.mapValues { e -> e.value.toSet() }
                    )
                }
                appCtx.getString(R.string.vm_sdk_everywhere_ok, rule.name, ok, targets.size, compWritten)
            }
            onDone(msg)
        }
    }

    /** 批库链修复③：per-app × 单 SDK 粒度开关（库页档案卡行开关专用） */
    fun setSdkForApp(pkg: String, ruleId: String, on: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy(if (on) "sdk-on" else "sdk-off", locked = true) {
                if (!rootGate()) return@withBusy appCtx.getString(R.string.vm_need_root)
                if (ledgerCorrupted()) return@withBusy appCtx.getString(R.string.vm_ledger_corrupted)
                val app = _state.value.apps.firstOrNull { it.packageName == pkg }
                    ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                val hit = app.matchedSdks.firstOrNull { it.ruleId == ruleId }
                    ?: return@withBusy appCtx.getString(R.string.vm_no_targets)
                val prev = applied.get(pkg)
                // 批FF1（七审 AV09）：off 分支的就地解释跨到共享尾段返回（恢复 N 项/外部保留 M 项）
                var removedEff: Set<String> = emptySet()
                if (on) {
                    // 批Y2（架构评审 C3）：native/provider 一并剔除——记账与写入同源（原记账含 provider）
                    val byType: Map<String, List<String>> = hit.componentTypes.entries
                        .groupBy({ it.value }, { it.key })
                        .filterKeys { it != "provider" && it != "native" }
                    if (byType.isEmpty()) return@withBusy appCtx.getString(R.string.vm_no_targets)
                    val r = engine.applyIfw(pkg, byType)
                    // 批X2（审查 R04/PN-06）：合并语义 + 失败如实反馈（不再静默成功）
                    if (r.isSuccess) applied.recordMerge(
                        pkg, Engine.IFW.name,
                        byType.values.flatten(),
                        byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap(),
                        addedRuleIds = setOf(ruleId)
                    ) else return@withBusy appCtx.getString(R.string.vm_op_fail)
                } else {
                    // off：仅移除该 SDK 命中的组件（IFW 定向移除 + pm enable）；
                    // 外部/其他来源 filter 幸存（removeIfw 语义批J#1 已修正）。
                    // 批BB RV-03：共用恢复所有权推导——
                    //   ① PM 只撤本桶实际负责且非 preExisting 的部分（仅 IFW 管理时不动 PM）；
                    //   ② 共享命中被其他 SDK 仍引用时保留（关闭 A 不破坏 B）；
                    //   ③ 移除的组件按桶过滤后整体替换。
                    val removed = hit.matchedComponents.toSet()
                    val retainedByOthers = hit.matchedComponents.filter { c ->
                        app.matchedSdks.any { o -> o.ruleId != ruleId && o.matchedComponents.contains(c) }
                    }.toSet()
                    val removedEffective = removed - retainedByOthers
                    removedEff = removedEffective
                    val pmBucket = prev?.pm
                    val pmTargets = removedEffective.filter { c ->
                        pmBucket?.components?.contains(c) == true && c !in pmBucket.preExisting.toSet()
                    }
                    // 批FF-B1①：pm enable 逐项执行（失败明细进 Operation）；账本语义保持原子——
                    // 任一失败=整体失败不删账本（保留重试依据，批X2 RV 语义）
                    val perItem = engine.enablePmPerItem(pkg, pmTargets)
                    if (perItem.isNotEmpty()) {
                        opCenter.record(
                            OperationKind.SET_SDK_FOR_APP, pkg,
                            perItem.map { (c, ok) -> OperationItem(c, ok, if (ok) "" else "pm enable failed") },
                            "",
                        )
                    }
                    val pmFailCount = perItem.count { !it.second }
                    val r1 = if (pmFailCount == 0) Result.success(pmTargets.size)
                    else Result.failure<Int>(IllegalStateException("pm enable: $pmFailCount failed"))
                    val r2 = engine.removeIfw(pkg, removeComponents = removedEffective)
                    // 批X2：写失败不谎报；账本替换语义——双桶全空自动删除（修 R04-b 复活问题）
                    check(r1.isSuccess && r2.isSuccess)
                    fun minus(b: AppliedRulesStore.EngineChange?) = b?.copy(
                        components = b.components.filter { it !in removedEffective },
                        types = b.types.filterKeys { it !in removedEffective },
                        preExisting = b.preExisting.filter { it !in removedEffective },
                        // 批BB RV-02：关闭 SDK 同步终止该桶内对此 SDK 的意图
                        ruleIds = b.ruleIds - ruleId
                    )?.takeIf { b2 -> b2.components.isNotEmpty() || b2.ruleIds.isNotEmpty() }
                    applied.replaceBuckets(
                        pkg,
                        ifw = minus(prev?.ifw),
                        pm = minus(prev?.pm),
                        fallbackRuleIds = prev?.ruleIds.orEmpty() - ruleId
                    )
                }
                val liveAfter = engine.readLiveDisabled(listOf(pkg)).first
                // 批FF2（七审 AV02）：写后同帧发布 applied——单 SDK 开关与批量路径同契约，
                // 统计/详情立即一致，不再读到过期账本快照
                _state.update {
                    it.copy(
                        applied = applied.all(),
                        ledgerCorrupted = applied.corrupted,
                        liveDisabled = liveAfter.let { l -> it.liveDisabled + (pkg to (l[pkg] ?: emptySet())) }
                    )
                }
                // 批FF1（七审 AV09）：off 成功给就地解释——恢复多少本工具管理项、多少由外部禁用保留
                //（所有权保护不误读为开关失灵）；计数按账本实际负责口径（批FF 复验修正：扫描命中
                //含从未被本工具写入的 provider，不得计入"已恢复"）；on 分支保持静默成功
                if (!on && removedEff.isNotEmpty()) {
                    val liveSet = liveAfter[pkg] ?: emptySet()
                    val restoredFromLedger = removedEff.count { c ->
                        prev?.ifw?.components?.contains(c) == true || prev?.pm?.components?.contains(c) == true
                    }
                    val keptExternal = hit.matchedComponents.count { c ->
                        c !in removedEff && liveSet.any { l -> l == c || l == "$pkg/$c" }
                    }
                    if (restoredFromLedger > 0 && keptExternal > 0) {
                        return@withBusy appCtx.getString(R.string.sdk_off_partial, restoredFromLedger, keptExternal)
                    }
                    if (restoredFromLedger > 0) return@withBusy appCtx.getString(R.string.sdk_off_full, restoredFromLedger)
                }
                ""
            }
            if (msg.isNotBlank()) onDone(msg)
        }
    }

    /** 档案卡开关状态：该 app 现场禁用集与该 SDK 命中组件有交集 = on。
     *  批EE5（六审 B05）：三态——live 未加载=null（UI 据此中性禁用，不再伪装"未禁用"）；无命中=false */
    fun sdkEnabledFor(app: ScannedApp, ruleId: String): Boolean? {
        val hit = app.matchedSdks.firstOrNull { it.ruleId == ruleId } ?: return false
        val live = _state.value.liveDisabled[app.packageName] ?: return null
        return hit.matchedComponents.any { c -> live.any { it == c || it == app.packageName + "/" + c } }
    }

    /** 批L4：禁用勾选的未识别组件（IFW 按类型分组；记 applied 供恢复）。
     *  批Y1（架构评审 C2）：结构化回调——拒绝/失败返回 (false, msg)，仅成功 (true, msg)；UI 据此保留/清空选择 */
    fun disableUnmatched(pkg: String, byType: Map<String, List<String>>, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (ok, msg) = withBusy("unmatched", locked = true) {
                if (!rootGate()) return@withBusy Pair(false, appCtx.getString(R.string.vm_need_root))
                if (ledgerCorrupted()) return@withBusy Pair(false, appCtx.getString(R.string.vm_ledger_corrupted))
                if (DisableEngine.isForbidden(pkg)) return@withBusy Pair(false, appCtx.getString(R.string.vm_forbidden))
                // 批Y2（架构评审 C3）：IFW 不支持 provider——执行/记账/回执同源过滤
                val eff = byType.filterKeys { it != "provider" }
                val comps = eff.values.flatten()
                if (comps.isEmpty()) return@withBusy Pair(false, appCtx.getString(R.string.vm_no_targets))
                if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                val r = engine.applyIfw(pkg, eff)
                if (r.isFailure) return@withBusy Pair(false, appCtx.getString(R.string.vm_op_fail))
                // 批E1/X2：合并语义
                applied.recordMerge(
                    pkg, Engine.IFW.name, comps,
                    eff.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap()
                )
                val liveAfter = engine.readLiveDisabled(listOf(pkg)).first
                _state.update {
                    it.copy(
                        applied = applied.all(),
                            ledgerCorrupted = applied.corrupted,
                        liveDisabled = liveAfter.let { l -> it.liveDisabled + (pkg to (l[pkg] ?: emptySet())) }
                    )
                }
                Pair(true, appCtx.getString(R.string.vm_apply_ok, "IFW", r.getOrDefault(0)))
            }
            onDone(ok, msg)
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
    // 批R4（审计 P-7）：declared/declarationsEnabled 已入 AppUiState（组合读 state，写后 _state.update 回显）

    init {
        viewModelScope.launch(Dispatchers.IO) {
            io.github.deserthouse.prunoid.core.engine.DeclarationsStore.read()
            _state.update {
                it.copy(
                    declared = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes,
                    declarationsEnabled = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.enabled
                )
            }
        }
    }

    /** 画圈/取消一个 SDK（ruleId → 其全部 packPrefixes 展开写声明文件） */
    fun toggleDeclaration(ruleId: String, on: Boolean, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            // 批S2 诊断探针：区分 rule 查找失败早退 / shell 写失败
            // 批X3（审查 PN-01）：声明写入受工作方式门控——audit 承诺"只读"，不得写声明文件
            if (!rootGate()) {
                onDone(appCtx.getString(R.string.vm_need_root))
                return@launch
            }
            val rule = rules.rule(ruleId)
            android.util.Log.d("SdkPruner", "decl.toggle: id=$ruleId found=${rule != null} prefixes=${rule?.packPrefixes?.size}")
            if (rule == null) return@launch
            // 批X1（审查 R01）：订阅前缀是外部输入，进白名单前先拒并反馈
            val badPrefix = rule.packPrefixes.firstOrNull { !it.matches(Regex("^[A-Za-z0-9_.\\$-]+$")) }
            if (badPrefix != null) {
                Log.d("SdkPruner", "decl.toggle: illegal prefix $badPrefix")
                onDone(appCtx.getString(R.string.decl_write_fail))
                return@launch
            }
            val cur = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes
            val next = if (on) cur + rule.packPrefixes.map { it.trimEnd('.') }
                       else cur - rule.packPrefixes.map { it.trimEnd('.') }.toSet()
            if (applied.corrupted) { onDone(appCtx.getString(R.string.vm_ledger_corrupted)); return@launch }
            // 批Z2：声明写入与其他 root 写入口共锁
            val r = OpCoordinator.write {
                io.github.deserthouse.prunoid.core.engine.DeclarationsStore.write(
                    getApplication<Application>().filesDir,
                    io.github.deserthouse.prunoid.core.engine.DeclarationsStore.Decl(
                        enabled = next.isNotEmpty(),
                        prefixes = next.sorted()
                    )
                )
            }
            if (r.isSuccess) {
                _state.update {
                    it.copy(declared = next.sorted().toSet(), declarationsEnabled = next.isNotEmpty())
                }
            }
            onDone(if (r.isSuccess) "" else (r.exceptionOrNull()?.message ?: "write failed"))
        }
    }

    fun setDeclarationsEnabled(on: Boolean, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            // 批X3（审查 PN-01）：同上，audit 模式下声明总开关写入一并拒绝
            if (!rootGate()) {
                onDone(appCtx.getString(R.string.vm_need_root))
                return@launch
            }
            if (applied.corrupted) { onDone(appCtx.getString(R.string.vm_ledger_corrupted)); return@launch }
            // 批Z2：声明写入与其他 root 写入口共锁
            val r = OpCoordinator.write {
                io.github.deserthouse.prunoid.core.engine.DeclarationsStore.write(
                    getApplication<Application>().filesDir,
                    io.github.deserthouse.prunoid.core.engine.DeclarationsStore.Decl(
                        enabled = on,
                        prefixes = io.github.deserthouse.prunoid.core.engine.DeclarationsStore.declaredPrefixes.sorted()
                    )
                )
            }
            // 批S2（审计 N-2）：失败不再谎报回显；错误文本资源化（设置页 Snackbar 直接展示）
            if (r.isSuccess) _state.update { it.copy(declarationsEnabled = on) }
            onDone(if (r.isSuccess) "" else appCtx.getString(R.string.decl_write_fail))
        }
    }

    // 批BB RV-07：扫描任务序号——同代次反序完成时仅最新轮发布（含 icons/live 子任务）
    private var scanSeq = 0

    fun rescan() {
        viewModelScope.launch {
            val mySeq = ++scanSeq
            _state.update { it.copy(scanning = true) }
            // 批X4（审查 R08/PN-17）：扫描索引随每次重扫重建——订阅刷新后新规则当场生效
            scanner = Scanner(appCtx, rules.published)
            // 批Z4/批BB RV-07：捕获规则代次与快照——扫描期间重建则旧代次结果不回写
            val genAtStart = rules.generation
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
            // 批Z4：扫描期间规则已重建 → 本轮结果为旧代次，丢弃不回写（避免旧索引结果覆盖新状态）
            if (rules.generation != genAtStart) {
                android.util.Log.d("SdkPruner", "scan discarded: rules generation changed during scan (${genAtStart} -> ${rules.generation})")
                _state.update { it.copy(scanning = false) }
                return@launch
            }
            if (mySeq != scanSeq) {
                // 批BB RV-07：同代次内已有更新一轮扫描——本轮不发布（防反序覆盖）
                android.util.Log.d("SdkPruner", "scan superseded by newer scan (seq $mySeq < $scanSeq)")
                return@launch
            }
            _state.update { it.copy(scanning = false, apps = apps, applied = withContext(Dispatchers.IO) { applied.all() }, ledgerCorrupted = applied.corrupted) }
            android.util.Log.d("SdkPruner", "ledgerSync: corrupted=${applied.corrupted}")
            // 图标后台预载（getApplicationIcon 是 binder 调用，100+ app 不能放组合期主线程）
            viewModelScope.launch(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val icons = apps.associate { a ->
                    a.packageName to (runCatching { pm.getApplicationIcon(a.packageName) }.getOrNull())
                }.filterValues { it != null }.mapValues { it.value!! }
                if (mySeq == scanSeq) _state.update { it.copy(icons = icons) }
            }
            // 批G：读现场——仅命中 app（shell 合并单次调用），IFW+pm 两处
            viewModelScope.launch(Dispatchers.IO) {
                val targets = apps.filter { it.matchedSdks.isNotEmpty() }.map { it.packageName }
                val (live, total) = engine.readLiveDisabled(targets)
                if (mySeq == scanSeq) _state.update { it.copy(liveDisabled = live, ifwTotal = total) }
            }
        }
    }

    fun toggleShowSystem() {
        _state.update { it.copy(filters = it.filters.copy(showSystem = !it.filters.showSystem)) }
    }

    /** 批FF-B4 补（方案B S2c/D06）：一键清空全部筛选——列表空态的就地恢复动作（U02 模式） */
    fun clearFilters() {
        _state.update { it.copy(filters = ListFilters()) }
    }

    fun toggleHitsOnly() {
        _state.update { it.copy(filters = it.filters.copy(hitsOnly = !it.filters.hitsOnly)) }
    }

    // 批T8：列表筛选三件套的 VM 入口
    fun toggleListCat(c: String) {
        _state.update { it.copy(filters = it.filters.copy(catSel = if (c in it.filters.catSel) it.filters.catSel - c else it.filters.catSel + c)) }
    }

    fun toggleListSafety(s: Safety) {
        _state.update { it.copy(filters = it.filters.copy(safety = if (it.filters.safety == s) null else s)) }
    }

    fun toggleListAppliedOnly() {
        _state.update { it.copy(filters = it.filters.copy(appliedOnly = !it.filters.appliedOnly)) }
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
        _state.update { it.copy(filters = ListFilters()) }
    }

    fun selectEngine(e: Engine) {
        userTouchedEngine = true
        _state.update { it.copy(engine = e) }
    }

    // ── 设置（设置页读写） ────────────────────────────────────────
    fun setDefaultEngine(name: String) {
        viewModelScope.launch {
            settings.setDefaultEngine(name)
            // 批X4（审查 PN-16）：只更新持久默认，不动详情页临时选择
            _state.update { it.copy(defaultEngine = Engine.entries.first { e -> e.name == name }) }
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
                android.util.Log.d("SdkPruner", "vm.setAutoReapply: starting guard")
                app.startForegroundService(Intent(app, RuleGuardService::class.java))
            } else {
                app.stopService(Intent(app, RuleGuardService::class.java))
            }
        }
    }

    // ── 多源订阅管理 ─────────────────────────────────────────────
    // 批G1：回调带 ok 位（UI 判成功不再解析消息文本）；消息一律此处资源化
    fun refreshSource(source: SettingsRepository.SubSource, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withBusy("refresh") { rules.refreshSource(source.id, source.url) }
            if (result.ok) {
                settings.updateSourceFetched(source.id, java.time.Instant.now().toString())
                rescan()
            }
            onDone(result.ok, subResultMessage(result))
        }
    }

    fun addSource(name: String, url: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val id = "src_" + url.hashCode().let { if (it < 0) -it else it }
            val result = withBusy("addsource") { rules.refreshSource(id, url) }
            if (result.ok) {
                val cur = _state.value.sources
                val newList = cur + SettingsRepository.SubSource(
                    id = id, name = name.ifBlank { url.substringAfter("//").substringBefore('/') },
                    url = url, lastFetched = java.time.Instant.now().toString()
                )
                settings.setSources(newList)
                // 批N1 修复：规则集已在 refreshSource→rebuild 更新，此处直接刷新 UI 态，
                // 不等 settings collect 回流（collect 回调里 sourcesChanged 会再次 rescan，
                // 与 refreshSource 内部的 rebuild 竞争曾致 ANR）
                rescan()
            }
            onDone(result.ok, subResultMessage(result))
        }
    }

    private fun subResultMessage(result: RuleSubscription.Result): String =
        if (result.ok) appCtx.getString(
            R.string.vm_sub_ok, result.entries,
            result.version.ifEmpty { appCtx.getString(R.string.unknown) }
        ) else appCtx.getString(R.string.vm_sub_fail, result.error)

    fun removeSource(source: SettingsRepository.SubSource, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (source.builtin) { onDone(false, appCtx.getString(R.string.vm_source_builtin)); return@launch }
            withContext(Dispatchers.IO) { rules.clearSourceCache(source.id) }
            settings.setSources(_state.value.sources.filterNot { it.id == source.id })
            onDone(true, appCtx.getString(R.string.vm_source_removed, source.name))
        }
    }


    fun allRules() = rules.allRules()

    fun ruleInfo(ruleId: String) = rules.rule(ruleId)

    // ── 备份与应急恢复 ────────────────────────────────────────────
    // 批R4（审计 P-6）：runBlocking 壳拆除改 suspend——组合期调用不再阻塞主线程
    suspend fun listBackups(): List<String> =
        runCatching { withContext(Dispatchers.IO) { engine.listBackups() } }
            .getOrDefault(emptyList())

    fun restoreBackup(path: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            var ok = false
            var partialNote = ""
            // 批BB RV-05：locked=false——restoreBackup 内部含共锁段与 PM 失败传播；
            // 账本同步按实际成功范围执行（失败桶保留恢复依据）
            val msg = withBusy("backup") {
                engine.restoreBackup(path).fold(
                    onSuccess = { report ->
                        ok = report.ifwOk
                        withContext(Dispatchers.IO) {
                            if (report.ifwOk) applied.removeEngine(Engine.IFW.name)
                            if (report.pmOk) applied.removeEngine(Engine.PM.name)
                        }
                        // 失败桶如实告知（PM 恢复失败→pm 账本保留=恢复依据仍在）
                        if (!report.pmOk && report.pmInArchive) {
                            partialNote = appCtx.getString(R.string.vm_restore_backup_pm_fail)
                        }
                        appCtx.getString(R.string.vm_restore_backup_ok)
                    },
                    onFailure = { appCtx.getString(R.string.vm_restore_backup_fail, it.message ?: "") }
                )
            }
            _state.update { it.copy(applied = withContext(Dispatchers.IO) { applied.all() }, ledgerCorrupted = applied.corrupted) }
            if (ok) rescan()  // 批P2#30：恢复后现场与记录可能漂移，重扫校准
            onDone(if (partialNote.isEmpty()) msg else msg + partialNote)
        }
    }

    /** 批BB RV-09：损坏账本显式重置（.corrupt 证据保留；恢复依据由用户确认丢弃） */
    fun resetLedger() {
        viewModelScope.launch {
            android.util.Log.d("SdkPruner", "resetLedger: invoked")
            withContext(Dispatchers.IO) { applied.resetCorrupted() }
            _state.update {
                val v = applied.corrupted
                android.util.Log.d("SdkPruner", "resetLedger: post-reset corrupted=$v")
                it.copy(applied = withContext(Dispatchers.IO) { applied.all() }, ledgerCorrupted = v)
            }
        }
    }

    fun clearAllIfw(onDone: (String) -> Unit) {
        viewModelScope.launch {
            var ok = false
            // 批BB RV-04：locked=false——clearIfwTransaction 内部持 OpCoordinator 共锁，
            // 成功才清 IFW 账本；失败原样保留恢复依据并如实报错
            val msg = withBusy("clearifw") {
                if (ledgerCorrupted()) return@withBusy appCtx.getString(R.string.vm_ledger_corrupted)
                DisableEngine.clearIfwTransaction(appCtx).fold(
                    onSuccess = { n ->
                        ok = true
                        appCtx.getString(R.string.vm_ifw_cleared, n)
                    },
                    onFailure = { appCtx.getString(R.string.vm_clear_fail, it.message ?: "") }
                )
            }
            _state.update { it.copy(applied = withContext(Dispatchers.IO) { applied.all() }, ledgerCorrupted = applied.corrupted) }
            if (ok) {
                // 批Y3：现场重读——IFW 已清，剩余禁用=pm 状态；IFW 总数以现读为准
                val pkgs = _state.value.liveDisabled.keys.toList()
                val (live, total) = withContext(Dispatchers.IO) {
                    if (pkgs.isEmpty()) emptyMap<String, Set<String>>() to 0
                    else engine.readLiveDisabled(pkgs).let { (m, t) -> m.mapValues { e -> e.value.toSet() } to t }
                }
                _state.update { it.copy(liveDisabled = it.liveDisabled + live, ifwTotal = total) }
            }
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
    fun applyRules(scanned: ScannedApp, selectedRuleIds: Set<String>, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val withBusyResult = withBusy("apply", locked = true) {
                if (!rootGate()) return@withBusy Pair(false, appCtx.getString(R.string.vm_need_root))
                if (ledgerCorrupted()) return@withBusy Pair(false, appCtx.getString(R.string.vm_ledger_corrupted))
                if (DisableEngine.isForbidden(scanned.packageName) || scanned.isSystem) return@withBusy Pair(false, appCtx.getString(R.string.vm_forbidden))
                val selected = scanned.matchedSdks.filter { it.ruleId in selectedRuleIds }
                // 批EE1（六审 B01）：共享组件去重——与 byTypeForVM 同口径
                val targets = selected.flatMap { it.matchedComponents }.distinct()
                if (targets.isEmpty()) return@withBusy Pair(false, appCtx.getString(R.string.vm_no_targets))
                val backup = if (_state.value.backupEnabled) engine.backup(_state.value.backupKeep).getOrNull() else null
                val byType = byTypeForVM(scanned, selectedRuleIds)
                // 批X2（审查 PN-03）：入口捕获引擎快照——执行/记账/结果引用同一份，消灭重读漂移
                val engineSnapshot = _state.value.engine
                // 批Y2（架构评审 C3）：IFW 只支持 activity/service/receiver——provider 在计划层剔除，
                // 执行/记账/回执同源；全 provider 选择=无支持目标，诚实失败不记账
                val effByType = if (engineSnapshot == Engine.IFW) byType.filterKeys { it != "provider" } else byType
                val effTargets = effByType.values.flatten()
                if (engineSnapshot == Engine.IFW && effByType.isEmpty()) {
                    return@withBusy Pair(false, appCtx.getString(R.string.vm_no_targets))
                }
                var preExistingTargets: List<String> = emptyList()
                // 批FF-B1①（AA7）：PM 逐项执行的实际成功集——下游记账只记真实写入的组件
                var pmOkTargets: List<String>? = null
                val r = when (engineSnapshot) {
                    Engine.IFW -> engine.applyIfw(scanned.packageName, effByType).also { r ->
                        // 批FF-B2：IFW 为文件级原子写——结果卡以单条目记录（重试参数随行）
                        if (r.isSuccess) {
                            opCenter.record(
                                OperationKind.APPLY, scanned.packageName,
                                listOf(OperationItem("ifw-file", true, "")),
                                appCtx.getString(R.string.vm_apply_ok, "IFW", r.getOrDefault(0)),
                                retryRuleIds = selectedRuleIds,
                            )
                        }
                    }
                    // 批Z3（架构评审 A5）：写入前现场读——已禁用的组件记 preExisting（非本工具新增，恢复跳过）。
                    // 批BB RV-01：本 PM 桶已负责的组件排除在外——它们是本工具自己的写入，
                    // 重复 apply 不得把自有禁用污染成"外部既有状态"
                    Engine.PM -> {
                        val pre = engine.readPmDisabled(scanned.packageName)
                        val owned = applied.get(scanned.packageName)?.pm?.components.orEmpty().toSet()
                        preExistingTargets = targets.filter { it in pre && it !in owned }
                        // 批FF-B1①（AA7/方案B S1）：PM 逐项执行——只记成功集，失败项进 Operation
                        val perItem = engine.applyPmPerItem(scanned.packageName, targets)
                        val okComps = perItem.filter { it.second }.map { it.first }
                        opCenter.record(
                            OperationKind.APPLY, scanned.packageName,
                            perItem.map { (c, ok) -> OperationItem(c, ok, if (ok) "" else "pm disable failed") },
                            appCtx.getString(R.string.vm_apply_ok, "PM", okComps.size),
                            retryRuleIds = selectedRuleIds,
                        )
                        pmOkTargets = okComps
                        // 全失败=诚实失败（UI 保留选择重试）；部分成功=记账成功集+回执如实计数
                        if (okComps.isEmpty()) {
                            Result.failure(IllegalStateException("pm disable: all ${targets.size} components failed"))
                        } else {
                            Result.success(okComps.size)
                        }
                    }
                }
                // 批X2（审查 R04/R05）：合并记账（不覆盖同包其他 SDK）+ ruleIds 身份入账
                if (r.isFailure) return@withBusy Pair(false, appCtx.getString(R.string.vm_op_fail))
                if (engineSnapshot == Engine.IFW) {
                    applied.recordMerge(
                        scanned.packageName, engineSnapshot.name, effTargets,
                        effByType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap(),
                        addedRuleIds = selectedRuleIds
                    )
                } else {
                    // 批FF-B1①：PM 记账只记实际成功集（部分失败组件不进账本=不产生虚假管理条目）
                    applied.recordMerge(
                        scanned.packageName, engineSnapshot.name, pmOkTargets ?: targets,
                        byType.entries.flatMap { (t, cs) -> cs.map { it to t } }.toMap(),
                        addedRuleIds = selectedRuleIds,
                        preExisting = preExistingTargets
                    )
                }
                // 批G：写入后单包重读现场（展示层以现场为准）
                val liveAfter = if (r.isSuccess) engine.readLiveDisabled(listOf(scanned.packageName)).first else null
                _state.update {
                    it.copy(
                        applied = applied.all(),
                            ledgerCorrupted = applied.corrupted,
                        liveDisabled = liveAfter?.let { l -> it.liveDisabled + (scanned.packageName to (l[scanned.packageName] ?: emptySet())) } ?: it.liveDisabled
                    )
                }
                buildString {
                    append(appCtx.getString(R.string.vm_apply_ok, engineSnapshot.name, r.getOrDefault(0)))
                    if (backup != null) append(appCtx.getString(R.string.vm_apply_backup))
                    r.exceptionOrNull()?.let { append(appCtx.getString(R.string.vm_fail_suffix, it.message ?: "")) }
                }.let { m -> Pair(r.isSuccess, m) }
            }
            // 批X2（审查 PN-06）：结构化结果——成功才由 UI 清空选择
            onDone(withBusyResult.first, withBusyResult.second)
        }
    }

    fun restoreApp(scanned: ScannedApp, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withBusy("restore", locked = true) {
                if (ledgerCorrupted()) return@withBusy appCtx.getString(R.string.vm_ledger_corrupted)
                // 按 applied 记录回滚（当时真实写入集），不用当前扫描重算——
                // 否则规则更新/显式加选的组件会成为恢复盲区。
                // 批Z1/Z3（架构评审 A3/A5）：按引擎桶分别恢复；PM 跳过 preExisting
                // （写入前已禁用的组件非本工具新增，恢复保持原状并披露）。
                val entry = applied.get(scanned.packageName)
                val ifwCh = entry?.ifw
                val pmCh = entry?.pm
                // 批P0#8：保留删除——只移除本工具 applied 的 filter，外部/其他来源 IFW 规则幸存
                val r1 = if (ifwCh != null) engine.removeIfw(scanned.packageName, removeComponents = ifwCh.components.toSet())
                else Result.success(0)
                val pmTargets = pmCh?.components?.filter { it !in pmCh.preExisting.toSet() }.orEmpty()
                val skippedPre = (pmCh?.components?.size ?: 0) - pmTargets.size
                // 批FF-B1①：pm enable 逐项（失败明细进 Operation）；账本原子语义保持——
                // 任一失败=恢复失败不删账本（PN-07 保留重试依据）
                val perItem = engine.enablePmPerItem(scanned.packageName, pmTargets)
                if (perItem.isNotEmpty()) {
                    opCenter.record(
                        OperationKind.RESTORE, scanned.packageName,
                        perItem.map { (c, ok) -> OperationItem(c, ok, if (ok) "" else "pm enable failed") },
                        "",
                    )
                }
                val pmFailCount = perItem.count { !it.second }
                val r2 = if (pmFailCount == 0) Result.success(pmTargets.size)
                else Result.failure<Int>(IllegalStateException("pm enable: $pmFailCount failed"))
                // 批X2（审查 PN-07）：失败不删恢复账本——现场仍禁用时必须保留重试依据
                val ok = r1.isSuccess && r2.isSuccess
                if (ok && entry != null) applied.remove(scanned.packageName)
                _state.update { it.copy(applied = applied.all()) }
                // 批G：恢复后单包重读现场
                val liveAfterR = if (ok) engine.readLiveDisabled(listOf(scanned.packageName)).first else null
                _state.update {
                    it.copy(
                        liveDisabled = liveAfterR?.let { l -> it.liveDisabled + (scanned.packageName to (l[scanned.packageName] ?: emptySet())) } ?: it.liveDisabled
                    )
                }
                if (ok) {
                    appCtx.getString(R.string.vm_restore_ok) +
                        if (skippedPre > 0) appCtx.getString(R.string.vm_restore_skip_pre, skippedPre) else ""
                } else appCtx.getString(R.string.vm_restore_fail, r1.exceptionOrNull()?.message ?: r2.exceptionOrNull()?.message ?: "")
            }
            onDone(msg)
        }
    }
}

/** 批债33：app 勾选 → 引擎类型分组（纯函数，供 VM 与单测共用）。
 *  批EE1（六审 B01）：distinctBy 去重共享组件——两 SDK 共含同一组件时组内只保留一份，
 *  消灭确认/回执双计数与重复 filter 写入 */
fun byTypeForVM(scanned: ScannedApp, ruleIds: Set<String>): Map<String, List<String>> =
    scanned.matchedSdks
        .filter { it.ruleId in ruleIds }
        .flatMap { it.componentTypes.entries }
        .distinctBy { it.key }
        .groupBy({ it.value }, { it.key })
