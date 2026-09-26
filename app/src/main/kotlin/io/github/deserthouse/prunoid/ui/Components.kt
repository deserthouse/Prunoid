package io.github.deserthouse.prunoid.ui
// M3E 视觉 + 审查清单落地：应用状态可见/手动重扫/系统 app 防误操作/逐 SDK 勾选/量化确认/busy 态/恢复双击确认。
// 包名/组件名/命令一律等宽；浏览=平铺，聚焦=卡片。本文件族由 AppScreens.kt 拆分（批Q2）。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.core.engine.AppliedRulesStore
import io.github.deserthouse.prunoid.core.engine.DisableEngine
import io.github.deserthouse.prunoid.core.engine.Engine
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private fun safetyIcon(s: Safety): ImageVectorAlias = when (s) {
    Safety.SAFE -> Icons.Outlined.Check
    Safety.CAUTION -> Icons.Outlined.WarningAmber
    Safety.RISKY -> Icons.Outlined.ErrorOutline
    Safety.UNKNOWN -> Icons.Outlined.HelpOutline
}

private typealias ImageVectorAlias = androidx.compose.ui.graphics.vector.ImageVector


/** 批L2：滚动方向判定（首个可见项索引/偏移回落 = 向上） */
val androidx.compose.foundation.lazy.LazyListState.scrollingUp: Boolean
    get() {
        val now = firstVisibleItemIndex to firstVisibleItemScrollOffset
        val prev = taggedScroll.getOrDefault(this.hashCode(), now)
        taggedScroll[this.hashCode()] = now
        return now.first < prev.first || (now.first == prev.first && now.second < prev.second)
    }
val taggedScroll = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()
/** 分类枚举 → 中文（schema 语言不穿透到 UI） */
@Composable
fun categoryLabel(c: String): String = stringResource(when (c) {
    "ads" -> R.string.cat_ads
    "push" -> R.string.cat_push
    "analytics" -> R.string.cat_analytics
    "quality" -> R.string.cat_quality
    "social_or_pay" -> R.string.cat_social
    "maps" -> R.string.cat_map
    "infra" -> R.string.cat_basic
    "security" -> R.string.cat_safety
    "framework" -> R.string.cat_framework
    else -> R.string.cat_other
})
/** SDK monogram 头像（LibChecker tonal avatar 语义）：规则 id 哈希取色，公司名/SDK 名首字母 */
private val MONOGRAM_COLORS = listOf(
    Color(0xFFB3E5FC) to Color(0xFF01579B),
    Color(0xFFFFCDD2) to Color(0xFF880E4F),
    Color(0xFFC8E6C9) to Color(0xFF1B5E20),
    Color(0xFFFFE0B2) to Color(0xFF7A4100),
    Color(0xFFD1C4E9) to Color(0xFF4527A0),
    Color(0xFFB2DFDB) to Color(0xFF004D40),
    Color(0xFFF8BBD0) to Color(0xFF880E4F),
    Color(0xFFCFD8DC) to Color(0xFF37474F)
)

/** 规则 id → LibChecker-Rules-Bundle 矢量图标名（Apache-2.0，assets/icons/lib_icons.json，构建期生成，覆盖主流 SDK） */
@Volatile private var libIconsCache: Map<String, String>? = null

private fun loadLibIcons(ctx: android.content.Context): Map<String, String> {
    libIconsCache?.let { return it }
    val m = runCatching {
        val json = ctx.assets.open("icons/lib_icons.json").bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(json)
    }.getOrDefault(emptyMap())
    libIconsCache = m
    return m
}

@Composable
fun SdkMonogram(ruleId: String, name: String, modifier: Modifier = Modifier, iconUrl: String? = null) {
    val dark = isDark()
    val (bg, fg) = MONOGRAM_COLORS[ruleId.hashCode().let { if (it < 0) -it else it } % MONOGRAM_COLORS.size]
    val bgC = if (dark) fg.copy(alpha = 0.25f) else bg
    val fgC = if (dark) MaterialTheme.colorScheme.onSurface else fg
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val iconName = remember { loadLibIcons(ctx)[ruleId] }
    val iconRes = iconName?.let {
        remember(it) {
            runCatching {
                val id = ctx.resources.getIdentifier(it, "drawable", ctx.packageName)
                if (id != 0) id else null
            }.getOrNull()
        }
    }
    val hasBrand = !iconUrl.isNullOrBlank() || iconRes != null
    // 品牌矢量分支对齐上游 LibChecker：中性浅底 + 原色渲染（tint 会把多色路径染成单色）
    val brandBg = if (dark) BrandTileDark else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .size(32.dp)
            .background(if (hasBrand) brandBg else bgC, RoundedCornerShape(50))
            .semantics { contentDescription = "SDK: $name" },
        contentAlignment = Alignment.Center
    ) {
        when {
            // 贡献者提供的品牌图标（iconUrl，懒加载）优先
            !iconUrl.isNullOrBlank() -> coil.compose.AsyncImage(
                model = iconUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            iconRes != null -> Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = Color.Unspecified
            )
            else -> Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = fgC
            )
        }
    }
}

/** 评级 → 用户语言后果说明（批#10） */
@Composable
fun safetyConsequence(s: Safety): String = stringResource(when (s) {
    Safety.SAFE -> R.string.cons_safe
    Safety.CAUTION -> R.string.cons_caution
    Safety.RISKY -> R.string.cons_risky
    Safety.UNKNOWN -> R.string.cons_unknown
})

/** 四级安全徽标：图标 + 文字（色不单独表意） */
@Composable
fun SafetyBadge(s: Safety, modifier: Modifier = Modifier) {
    val dark = isDark()
    val c = safetyColors(s, dark)
    Surface(
        color = c.container,
        contentColor = c.onContainer,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        // semantics{} 非组合上下文——文案先在组合期解析（既有教训）
        val badgeCd = safetyLabel(s) + " — " + safetyConsequence(s)
        Row(
            Modifier
                .semantics { contentDescription = badgeCd }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(safetyIcon(s), contentDescription = null, modifier = Modifier.size(13.dp))
            Text(safetyLabel(s), style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable
fun rememberSnackbar(): SnackbarHostState = remember { SnackbarHostState() }

@Composable
fun SnackbarEffect(snackbar: SnackbarHostState, message: String?, seq: Any? = null) {
    // 批J#4：seq 参与 key——同文本连续两次（如两次 Apply 成功）也能弹出
    LaunchedEffect(message, seq) {
        message?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it, withDismissAction = true) }
    }
}

fun fmtTime(epochMs: Long): String = if (epochMs <= 0) "" else
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
/** 引擎显示名（资源化，非组合上下文不可用 label） */
@Composable
fun engineLabel(e: Engine): String = stringResource(if (e == Engine.IFW) R.string.engine_ifw_short else R.string.engine_pm_short)

@Composable
fun typeLabel(t: String): String = stringResource(when (t) {
    "activity" -> R.string.comp_activity
    "service" -> R.string.comp_service
    "receiver" -> R.string.comp_receiver
    "provider" -> R.string.comp_provider
    else -> R.string.comp_other
})

@Composable
fun confidenceLabel(c: String): String = if (c == "high" || c == "medium" || c == "low")
    stringResource(when (c) {
        "high" -> R.string.conf_high
        "medium" -> R.string.conf_medium
        else -> R.string.conf_low
    }) else c

@Composable
fun safetyLabel(s: Safety): String = stringResource(when (s) {
    Safety.SAFE -> R.string.safe_safe
    Safety.CAUTION -> R.string.safe_caution
    Safety.RISKY -> R.string.safe_risky
    Safety.UNKNOWN -> R.string.safe_unknown
})


/** SDK 档案卡底部弹层（LibChecker 结构 ⊕ Blocker 结构化字段 ⊕ 量化句） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveFieldCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

/** SDK 类别单一来源（批Q2：列表筛选 sheet 与 SDK 库共用，加分类只改这里） */
val ALL_CATEGORIES = listOf("ads", "push", "analytics", "quality", "social_or_pay", "maps", "infra", "security", "framework", "other")

@Composable
fun tagText(t: String): String = typeLabel(t)

/** 统一分区标题（批Q5）：静态文本不占用 primary 语义，让位给可点项 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** 统一搜索框（批Q4）：多处复制粘贴样式的单一来源；placeholder 按语境传入 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { Text(stringResource(R.string.search_hint)) }
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = placeholder,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_search))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}
