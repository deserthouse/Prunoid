package io.github.deserthouse.prunoid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
        content = content
    )
}
