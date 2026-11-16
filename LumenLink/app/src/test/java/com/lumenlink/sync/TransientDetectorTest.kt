package com.lumenlink.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * TransientDetector 的回归测试。
 *
 * 重点锁住两个曾经出过的严重 bug：
 *  1. **启动即误触发**：slow 初始值过小导致 riseRatio 虚高，一开机就疯狂触发
 *  2. **误触发无法自愈**：slow 只在非瞬态时更新，一旦误判就永久卡死
 *
 * 这两个 bug 叠加的表现就是"太灵敏，完全用不了"。
 */
class TransientDetectorTest {

    private val sr = 44_100
    private val block = sr / 50   // 20ms

    private fun steadyRms(amp: Float, seed: Int): ShortArray {
        // 稳态正弦，模拟持续环境声
        return ShortArray(block) { i ->
            (sin(2 * Math.PI * 440.0 * i / sr).toFloat() * amp * 32767f).toInt().toShort()
        }
    }

    private fun noiseBlock(amp: Float, seed: Long): ShortArray {
        var state = seed
        return ShortArray(block) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val r = ((state ushr 33) % 20001L - 10000L).toFloat() / 10000f
            (r * amp * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private val gates = TransientDetector.spectrumGatesFor(0.75f)

    @Test
    fun `启动预热期不触发`() {
        val d = TransientDetector(sr)
        d.applySensitivity(0.75f)
        val prev = floatArrayOf(0f)
        var hits = 0
        // 前 15 块是预热期，即使有较大声音也不应触发
        for (k in 0 until 15) {
            val buf = noiseBlock(0.15f, k.toLong() + 1)
            val hit = d.process(buf, block, prev, k * 20_000_000L, null, false, gates)
            if (hit != null) hits++
        }
        assertEquals("预热期内不应触发", 0, hits)
    }

    @Test
    fun `稳定噪声不会持续误触发`() {
        val d = TransientDetector(sr)
        d.applySensitivity(0.75f)
        val prev = floatArrayOf(0f)
        var hits = 0
        // 300 块（6 秒）稳定的宽带噪声，不应有任何触发
        for (k in 0 until 300) {
            val buf = noiseBlock(0.10f, k.toLong() * 7919L + 1)
            val hit = d.process(buf, block, prev, k * 20_000_000L, null, false, gates)
            if (hit != null) hits++
        }
        assertTrue("稳定噪声不应持续触发，实际触发 $hits 次", hits <= 1)
    }

    @Test
    fun `稳定噪声后出现瞬态能正确触发`() {
        val d = TransientDetector(sr)
        d.applySensitivity(0.75f)
        val prev = floatArrayOf(0f)
        // 先用稳定噪声建立参考
        for (k in 0 until 150) {
            d.process(noiseBlock(0.02f, k.toLong() + 1), block, prev, k * 20_000_000L, null, false, gates)
        }
        // 再来一个强瞬态
        val burst = noiseBlock(0.85f, 999999L)
        val hit = d.process(burst, block, prev, 150 * 20_000_000L, null, false, gates)
        assertTrue("安静环境下的强瞬态应该触发", hit != null)
    }

    @Test
    fun `slow 包络始终跟随不被卡死`() {
        val d = TransientDetector(sr)
        d.applySensitivity(0.75f)
        val prev = floatArrayOf(0f)
        // 预热
        for (k in 0 until 20) {
            d.process(noiseBlock(0.05f, k.toLong() + 1), block, prev, k * 20_000_000L, null, false, gates)
        }
        val before = d.noiseFloor
        // 输入一个很大的持续信号，slow 应该跟上来
        for (k in 0 until 100) {
            d.process(noiseBlock(0.40f, k.toLong() + 5000), block, prev, (20 + k) * 20_000_000L, null, false, gates)
        }
        val after = d.noiseFloor
        assertTrue(
            "slow 包络应该跟随响度上升：before=$before after=$after",
            after > before * 2f
        )
    }

    @Test
    fun `静音输入不触发`() {
        val d = TransientDetector(sr)
        d.applySensitivity(1.0f)  // 最高灵敏度
        val prev = floatArrayOf(0f)
        val silent = ShortArray(block)
        for (k in 0 until 50) {
            val hit = d.process(silent, block, prev, k * 20_000_000L, null, false, gates)
            assertNull("静音不应触发", hit)
        }
    }

    @Test
    fun `灵敏度提高后阈值应当下降`() {
        val low = TransientDetector(sr).apply { applySensitivity(0.2f) }
        val high = TransientDetector(sr).apply { applySensitivity(0.9f) }
        assertTrue(
            "高灵敏度的上升沿门槛应更低",
            high.riseGate < low.riseGate
        )
        assertTrue(
            "高灵敏度的绝对下限应更低",
            high.absoluteFloor < low.absoluteFloor
        )
    }
}
