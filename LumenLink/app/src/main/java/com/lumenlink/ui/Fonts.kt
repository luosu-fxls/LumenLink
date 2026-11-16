package com.lumenlink.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.lumenlink.R

/**
 * 拉丁字体族（Oxanium Light）。
 *
 * 覆盖数字 0-9 与英文字母 A-Z / a-z，用于数值、版本号、录音源标识等。
 *
 * ⚠️ 只用于**拉丁字符**的显示。Oxanium 没有中文字形，整串套上去会让中文
 * 逐字 fallback 到系统字体，同一行里出现两种基线。中英混排请用 [latinSpans]。
 *
 * 字体许可：SIL Open Font License 1.1（Copyright 2019 Severin Meyer），
 * 允许随软件自由嵌入与再分发。
 *
 * 延迟初始化：首次用到时才由系统 FontManager 加载，避免拖慢冷启动。
 */
val LatinFontFamily: FontFamily by lazy {
    FontFamily(Font(R.font.oxanium))
}

/**
 * 拉丁字符的显示字重。
 *
 * 包里只放了 Oxanium-Light 这一个字形，没有 Medium/Bold 文件。
 * 请求 Bold 时系统会对 Light 轮廓做**合成加粗**（横向描边），
 * 这样既保持了你要的 Light 字形骨架，又能让数字在深色背景上够醒目。
 *
 * 如果之后觉得太粗或不够粗，改这一行即可全局生效；
 * 也可以把同目录下的 Oxanium-Bold.ttf 一起打包，在字体族里声明真实字重。
 */
val LatinFontWeight: FontWeight = FontWeight.Bold

/**
 * 判断某个字符是否该用 Oxanium 渲染。
 *
 * Oxanium 是**拉丁字体**（A-Z / a-z / 0-9 + 常用符号），没有中文字形。
 * 所以这里把 ASCII 可见字符算作"该换字体"的范围：
 *   - 数字 0-9
 *   - 英文大小写字母（录音源会显示 VOICE_RECOGNITION 这类词）
 *   - 数值/代码常用符号 . - % / : _ ( ) 等
 *
 * 中文、全角标点、以及 emoji（代理对）都返回 false，保持系统字体。
 *
 * 空格**不算**拉丁字符：这样「把手机 LED 变成」里的 LED 两侧空格
 * 归到中文一侧，中英文间距按系统默认走，不会被自定义字体的空格宽度带偏。
 * 波浪号 `~` 也不在其中，保持系统字体。
 */
internal fun Char.isLatinChar(): Boolean =
    this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' ||
        this in ".-%/:_,()[].+×*\"'"

/**
 * 把字符串切成「拉丁段 / 非拉丁段」交替的区间列表。
 *
 * 返回 (起始下标, 结束下标, 是否拉丁)。相邻同类型字符合并成一段。
 * 抽成纯函数是为了能脱离 Compose 写单元测试——[latinSpans] 本身是
 * @Composable，无法在 JVM 单测里直接调用。
 */
internal fun latinRanges(text: String): List<Triple<Int, Int, Boolean>> {
    val out = mutableListOf<Triple<Int, Int, Boolean>>()
    var i = 0
    while (i < text.length) {
        val latin = text[i].isLatinChar()
        val start = i
        while (i < text.length && text[i].isLatinChar() == latin) i++
        out += Triple(start, i, latin)
    }
    return out
}

/**
 * 把字符串里**每一段连续的拉丁字符（数字 + 英文）**套上 Oxanium + [LatinFontWeight]，
 * 其余（中文 / 全角标点 / 空格 / emoji）保持原字体原字重。
 *
 * 用于「已触发 12 次」「录音源 VOICE_RECOGNITION」这类中英混排文本：
 * 既要英文数字好看，又不能让中文失配，也不能把中文一起加粗
 * （中文加粗会显得笨重，且与正文其他中文不一致）。
 */
@Composable
fun latinSpans(text: String): AnnotatedString {
    val family = LatinFontFamily
    val weight = LatinFontWeight
    return buildAnnotatedString {
        for ((start, end, latin) in latinRanges(text)) {
            if (latin) {
                withStyle(SpanStyle(fontFamily = family, fontWeight = weight)) {
                    append(text.substring(start, end))
                }
            } else {
                append(text.substring(start, end))
            }
        }
    }
}
