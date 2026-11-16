package com.lumenlink.nikon

import kotlin.math.roundToInt

/**
 * 相机参数文本 ↔ 数值。尼康在属性里用的是人类可读字符串（"1/250"、"0.5\""、"ISO 400"），
 * 所以这里做双向转换，既用于 UI 展示也用于计算 LED 点亮窗口。
 */
object NikonMath {

    private val FRACTION = Regex("^(\\d+)\\s*/\\s*(\\d+)$")

    /**
     * "1/250" -> 4(ms)；"0.5\"" -> 500；"1.3\"" -> 1300；"30\"" -> 30000。
     * 无法解析时返回 0。
     */
    fun shutterTextToMillis(text: String): Int {
        val t = text.trim().lowercase().replace(" ", "")
        if (t.isEmpty()) return 0

        FRACTION.find(t)?.let { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return 0
            val den = m.groupValues[2].toIntOrNull() ?: return 0
            if (den == 0) return 0
            return ((num.toLong() * 1000) / den).toInt().coerceAtLeast(1)
        }

        // 整秒/小数秒写法："0.5" 或 0.5" 或 0.5s
        val secs = t.removeSuffix("\"").removeSuffix("s")
        val d = secs.toDoubleOrNull() ?: return 0
        if (d <= 0) return 0
        return (d * 1000).roundToInt()
    }

    /** 反过来：4 -> "1/250"，500 -> "0.5\""。用于把滑杆值写回相机。 */
    fun millisToShutterText(ms: Int): String {
        if (ms <= 0) return "1/250"
        return if (ms >= 1000) {
            val s = ms / 1000.0
            if (s == s.toInt().toDouble()) "${s.toInt()}\"" else "%.1f\"".format(s)
        } else {
            "1/${(1000.0 / ms).roundToInt()}"
        }
    }

    /** "ISO 400" / "400" / "Hi 1" -> 数值（Hi/Lo 这类返回 null，交给 UI 原样显示） */
    fun parseIso(text: String): Int? {
        val t = text.trim().uppercase().removePrefix("ISO").trim()
        return t.toIntOrNull()
    }
}
