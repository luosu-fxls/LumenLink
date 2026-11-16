package com.lumenlink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [latinRanges] 的切分行为测试。
 *
 * 这个函数决定哪些字符用 Oxanium 渲染，切错了就会出现
 * 中文被套上无字形字体（整行回退）或英文没换字体的问题。
 */
class LatinRangesTest {

    /** 取出被判为拉丁的片段文本，便于断言。 */
    private fun latinParts(text: String): List<String> =
        latinRanges(text).filter { it.third }.map { text.substring(it.first, it.second) }

    /** 取出被判为非拉丁的片段文本。 */
    private fun otherParts(text: String): List<String> =
        latinRanges(text).filterNot { it.third }.map { text.substring(it.first, it.second) }

    @Test
    fun `纯中文不切出任何拉丁段`() {
        assertEquals(emptyList<String>(), latinParts("录音源"))
    }

    @Test
    fun `纯数字被识别为拉丁段`() {
        assertEquals(listOf("12345"), latinParts("12345"))
    }

    @Test
    fun `纯英文被识别为拉丁段`() {
        assertEquals(listOf("VOICE_RECOGNITION"), latinParts("VOICE_RECOGNITION"))
    }

    @Test
    fun `中英混排只切出英文`() {
        assertEquals(listOf("App"), latinParts("让 App 认识你的相机快门声"))
    }

    @Test
    fun `中数混排只切出数字`() {
        assertEquals(listOf("12"), latinParts("已触发 12 次"))
    }

    @Test
    fun `小数和百分号作为一段`() {
        assertEquals(listOf("0.85"), latinParts("音色门限 0.85"))
        assertEquals(listOf("35%"), latinParts("进度 35%"))
    }

    @Test
    fun `斜杠快门速度保持一段`() {
        // 1/1000s 整体是拉丁段，'s' 也是字母
        assertEquals(listOf("1/1000s"), latinParts("快于 1/1000s"))
    }

    @Test
    fun `RGB 三组数字分别成为拉丁段`() {
        assertEquals(listOf("R", "255", "G", "128", "B", "0"), latinParts("R 255  G 128  B 0"))
    }

    @Test
    fun `空格不归入拉丁段`() {
        // 「把手机 LED 变成」：LED 两侧空格算作中文侧，保证中英文间距走系统默认
        val parts = latinRanges("把手机 LED 变成")
        val ledRange = parts.first { it.third }
        assertEquals("LED", "把手机 LED 变成".substring(ledRange.first, ledRange.second))
    }

    @Test
    fun `全角标点归入中文侧`() {
        val text = "快于 1/30s 的场景"
        // 「，」是全角逗号，不应出现在拉丁片段里
        assertTrue(latinParts(text).none { it.contains('，') })
    }

    @Test
    fun `切分结果首尾相接且完整覆盖原串`() {
        val text = "已触发 12 次，录音源 VOICE_RECOGNITION，电平 0.02341"
        val ranges = latinRanges(text)
        assertEquals(0, ranges.first().first)
        assertEquals(text.length, ranges.last().second)
        // 相邻区间不能有缝隙
        for (i in 0 until ranges.size - 1) {
            assertEquals(ranges[i].second, ranges[i + 1].first)
        }
    }

    @Test
    fun `空串返回空列表`() {
        assertEquals(emptyList<Triple<Int, Int, Boolean>>(), latinRanges(""))
    }

    @Test
    fun `isLatinChar 对中文和符号的判断`() {
        assertTrue('A'.isLatinChar())
        assertTrue('z'.isLatinChar())
        assertTrue('0'.isLatinChar())
        assertFalse('中'.isLatinChar())
        assertFalse('，'.isLatinChar())
        assertFalse(' '.isLatinChar())
        assertFalse('「'.isLatinChar())
    }
    @Test
    fun `建议 6到10 条 这行的数字能被切出来`() {
        val text = "条数越多模板越稳，但要多按几次快门。建议 6~10 条。"
        val parts = latinRanges(text).filter { it.third }.map { text.substring(it.first, it.second) }
        println("[DIAG] 切出的拉丁段 = $parts")
        assertTrue("应切出 6 和 10，实际 $parts", parts.contains("6") && parts.contains("10"))
    }

    @Test
    fun `波形号不应吞掉数字`() {
        val text = "建议 6~10 条"
        val parts = latinRanges(text).filter { it.third }.map { text.substring(it.first, it.second) }
        assertEquals(listOf("6", "10"), parts)
    }
}