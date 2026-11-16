package com.lumenlink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFC53D)
private val Cyan = Color(0xFF4FC3F7)
private val Bg = Color(0xFF0B0E11)
private val Surface1 = Color(0xFF151A20)
private val Surface2 = Color(0xFF1E252D)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF201A00),
    secondary = Cyan,
    onSecondary = Color(0xFF00212E),
    background = Bg,
    onBackground = Color(0xFFE6EAF0),
    surface = Surface1,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB0BAC6),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A6100),
    secondary = Color(0xFF00658A),
)

@Composable
fun LumenTheme(
    accent: com.lumenlink.ui.AppAccent = com.lumenlink.ui.AppAccent.Amber,
    /** 非空时使用自定义 RGB，忽略 [accent]。 */
    customRgb: IntArray? = null,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val rgb = customRgb ?: accent.rgb()
    val c = Color(rgb[0], rgb[1], rgb[2])
    val scheme = if (dark) {
        DarkColors.copy(
            primary = c,
            // 关键：按钮文字颜色取决于**强调色本身的亮度**。
            // 固定深色字在"素白"这类浅色主题下会变成深字+白底看不清，
            // 固定白字在"紫罗兰"上又会糊掉。这里按相对亮度二选一。
            onPrimary = if (isLightColor(rgb)) Color(0xFF1A1400) else Color(0xFF11161C),
            secondary = c.copy(alpha = 0.85f),
        )
    } else {
        LightColors.copy(
            primary = darkenForLightTheme(rgb),
            onPrimary = Color.White,
            secondary = darkenForLightTheme(rgb),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 感知亮度（Rec. 601），0..1。 */
private fun luminance(rgb: IntArray): Float =
    (0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]) / 255f

/** 是否属于"浅色"，浅色上用深字。 */
private fun isLightColor(rgb: IntArray): Boolean = luminance(rgb) > 0.60f

/** 浅色系统主题下把强调色压暗，保证白字可读。 */
private fun darkenForLightTheme(rgb: IntArray): Color {
    val f = if (luminance(rgb) > 0.55f) 0.55f else 0.85f
    return Color(
        (rgb[0] * f).toInt().coerceIn(0, 255),
        (rgb[1] * f).toInt().coerceIn(0, 255),
        (rgb[2] * f).toInt().coerceIn(0, 255),
    )
}
