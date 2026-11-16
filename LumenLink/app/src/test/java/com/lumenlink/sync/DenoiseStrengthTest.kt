package com.lumenlink.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 验证「降噪强度」这个滑块**真的有效果差异**。
 *
 * 背景：用户反馈"降噪强度还是没变"。之前只有 `关闭降噪时信号保持不变` 这类
 * 定性测试，没有任何一个用例真正比较不同 strength 值下的抑制量，
 * 所以公式即使退化成常数也测不出来。
 *
 * 这里用同一段带稳定嗡声的输入，分别跑 strength = 0.0 / 0.5 / 1.0，
 * 断言抑制量随强度单调增强。
 */
class DenoiseStrengthTest {

    private val sr = 44_100
    private val block = 1024

    /** 稳态正弦（模拟底噪/嗡声）。 */
    private fun tone(freq: Double, amp: Float, n: Int = block, phase0: Double = 0.0): ShortArray =
        ShortArray(n) { i -> (sin(2 * Math.PI * freq * i / sr + phase0) * amp * 32767f).toInt().toShort() }

    private fun rmsOf(a: ShortArray, n: Int = a.size): Float {
        var s = 0.0
        for (i in 0 until n) {
            val x = a[i] / 32768.0
            s += x * x
        }
        return kotlin.math.sqrt(s / n).toFloat()
    }

    /**
     * 用固定强度跑一段稳定噪声，返回"输出 RMS / 输入 RMS"。
     * 数值越小表示压得越狠。
     */
    private fun suppressionRatio(strength: Float, warmup: Int = 40, measure: Int = 40): Float {
        val d = NoiseSuppressor(block)
        d.setStrength(strength)
        d.enabled = true
        // 让噪声估计收敛（指数平滑 alpha≈0.013，需要几十帧）
        repeat(warmup) {
            val b = tone(440.0, 0.15f, block, it * 0.37)
            d.process(b, block)
        }
        // 再测一段，统计输入输出能量
        var inE = 0.0
        var outE = 0.0
        repeat(measure) {
            val raw = tone(440.0, 0.15f, block, it * 0.91)
            val copy = raw.copyOf()
            inE += rmsOf(copy, block).toDouble().let { r -> r * r }
            d.process(raw, block)
            outE += rmsOf(raw, block).toDouble().let { r -> r * r }
        }
        return (outE / inE).toFloat()
    }

    @Test
    fun `强度越高抑制越强`() {
        val low = suppressionRatio(0.0f)
        val mid = suppressionRatio(0.5f)
        val high = suppressionRatio(1.0f)

        println("[DIAG] strength 0.0 -> ratio=$low")
        println("[DIAG] strength 0.5 -> ratio=$mid")
        println("[DIAG] strength 1.0 -> ratio=$high")

        assertTrue("强度 0.5 应比 0.0 压得更狠，实际 $mid vs $low", mid < low)
        assertTrue("强度 1.0 应比 0.5 压得更狠，实际 $high vs $mid", high < mid)
    }

    @Test
    fun `强度差异足够明显`() {
        val low = suppressionRatio(0.0f)
        val high = suppressionRatio(1.0f)
        // 0 到 1 之间至少要拉开 10% 的相对差距，否则滑块在真机上"感觉不到"
        val rel = (low - high) / low
        println("[DIAG] 0.0 vs 1.0 相对差 = ${"%.3f".format(rel)}")
        assertTrue("强度 0→1 的抑制差异太小（${"%.3f".format(rel)}），滑块会显得无效", rel > 0.10f)
    }

    @Test
    fun `关闭时完全不改变信号`() {
        val d = NoiseSuppressor(block)
        d.enabled = false
        val src = tone(440.0, 0.2f)
        val expect = src.copyOf()
        d.process(src, block)
        assertTrue("关闭降噪后不应改动样本", src.contentEquals(expect))
    }
}
