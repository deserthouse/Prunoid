package io.github.deserthouse.prunoid.ui

import android.os.Build
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.core.rules.Safety

// 安全四级语义色：固定色相、container/onContainer 成对、暗色降 tone 不换色相。
// 不随动态取色漂移——它们承载含义；色不单独表意，徽标必须带图标+文字。
data class SafetyColors(val container: Color, val onContainer: Color)

private val SafeLight = SafetyColors(Color(0xFFB9F2C4), Color(0xFF003912))
private val SafeDark = SafetyColors(Color(0xFF1E5025), Color(0xFFB9F2C4))
private val CautionLight = SafetyColors(Color(0xFFFFDCBE), Color(0xFF4A2800))
private val CautionDark = SafetyColors(Color(0xFF553F1D), Color(0xFFFFDCBE))
private val RiskyLight = SafetyColors(Color(0xFFFFDAD5), Color(0xFF410001))
private val RiskyDark = SafetyColors(Color(0xFF5C1A14), Color(0xFFFFDAD5))
private val UnknownLight = SafetyColors(Color(0xFFE3E2E6), Color(0xFF45464B))
private val UnknownDark = SafetyColors(Color(0xFF3A3A3F), Color(0xFFE3E2E6))

// 无 root 警示条（fixed 橙系）
val WarnContainerLight = Color(0xFFFFE3C4)
val WarnOnContainerLight = Color(0xFF4A2E00)
val WarnContainerDark = Color(0xFF52431F)
val WarnOnContainerDark = Color(0xFFFFE3C4)

// 暗色下品牌矢量头像的托底浅灰（深色品牌矢量在暗色卡上黑对黑不可见——批U1）
val BrandTileDark = Color(0xFFC6C4CE)

// 形状 role 化（批Q1）：替代散布的手写圆角。胶囊一律 CircleShape。
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// 动效规格（批Q2）："克制 100~500ms tween"契约的代码载体；图表入场单独放行 600ms。
object MotionTokens {
    const val FAST_MS = 150
    const val SHEET_MS = 300
    const val CHART_MS = 600
    // 常用类型直接给命名 token，调用点免显式泛型
    val fastFloat: TweenSpec<Float> = tween(FAST_MS, easing = FastOutSlowInEasing)
    val fastSize: TweenSpec<androidx.compose.ui.unit.IntSize> = tween(FAST_MS, easing = FastOutSlowInEasing)
    fun <T> sheet(): TweenSpec<T> = tween(SHEET_MS, easing = FastOutSlowInEasing)
    fun <T> chart(): TweenSpec<T> = tween(CHART_MS, easing = FastOutSlowInEasing)
}

// 统计图表专用调色板（批Q6）：避开安全语义色相（红/橙/绿/中性灰），
// 防止"分类占比"被误读为"风险等级"；亮暗两套，色相一一对应。
fun chartColor(cat: String, dark: Boolean): Color = when (cat) {
    "ads" -> if (dark) Color(0xFFB39DDB) else Color(0xFF5E35B1)
    "push" -> if (dark) Color(0xFFF48FB1) else Color(0xFFAD1457)
    "analytics" -> if (dark) Color(0xFF80DEEA) else Color(0xFF00838F)
    "quality" -> if (dark) Color(0xFFBCAAA4) else Color(0xFF6D4C41)
    "social_or_pay" -> if (dark) Color(0xFF9FA8DA) else Color(0xFF3949AB)
    "maps" -> if (dark) Color(0xFF4DD0E1) else Color(0xFF00ACC1)
    "framework" -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
    "infra" -> if (dark) Color(0xFF90A4AE) else Color(0xFF455A64)
    "security" -> if (dark) Color(0xFFFFF59D) else Color(0xFFF9A825)
    else -> if (dark) Color(0xFFB0BEC5) else Color(0xFF546E7A)
}

fun safetyColors(s: Safety, darkTheme: Boolean): SafetyColors = when (s) {
    Safety.SAFE -> if (darkTheme) SafeDark else SafeLight
    Safety.CAUTION -> if (darkTheme) CautionDark else CautionLight
    Safety.RISKY -> if (darkTheme) RiskyDark else RiskyLight
    Safety.UNKNOWN -> if (darkTheme) UnknownDark else UnknownLight
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SdkPrunerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        // minSdk 31: dynamic color available on every supported device
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
