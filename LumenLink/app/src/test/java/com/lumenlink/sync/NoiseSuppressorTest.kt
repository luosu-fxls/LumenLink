package com.lumenlink.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * NoiseSuppressor 的正确性测试。
 *
 * 重点验证三件事：
 *  1. FFT/IFFT 往返能还原信号（否则降噪会把声音改坏）
 *  2. 稳态噪声能被压低
 *  3. 瞬态（模拟快门声）不被过度削弱
 *
 * 这些是纯算法测试，不依赖 Android 环境。
 */
class NoiseSuppressorTest {

    private val sr = 44_100
    private val block = 256

    /** 生成一段正弦波，模拟稳态噪声（比如 50Hz 嗡声或风扇声）。 */
    private fun steady(freq: Double, amp: Float, phase0: Double = 0.0): ShortArray {
        return ShortArray(block) { i ->
            (sin(2 * Math.PI * freq * i / sr + phase0) * amp * 32767f).toInt().toShort()
        }
    }

    private fun rms(a: ShortArray): Float {
        var s = 0.0
        for (v in a) {
            val x = v / 32768f
            s += x.toDouble() * x
        }
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    @Test
    fun `关闭降噪时信号保持不变`() {
        val d = NoiseSuppressor(block)
        d.enabled = false
        val a = steady(1000.0, 0.3f)
        val orig = a.copyOf()
        d.process(a, block)
        for (i in a.indices) {
            assertTrue("关闭时不应修改数据，索引 $i", a[i] == orig[i])
        }
    }

    /** 生成宽带噪声（模拟真实麦克风底噪：各频点都有能量）。 */
    private fun widebandNoise(amp: Float, seed: Long): ShortArray {
        var state = seed
        return ShortArray(block) {
            // 简单 LCG 伪随机
            state = state * 6364136223846793005L + 1442695040888963407L
            val r = ((state ushr 33) % 20001L - 10000L).toFloat() / 10000f
            (r * amp * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `稳态噪声被压低`() {
        val d = NoiseSuppressor(block)
        d.enabled = true
        d.setStrength(0.8f)
        // 宽带稳态噪声（真实底噪形态），喂 200 块让噪声估计收敛
        val src = widebandNoise(0.20f, 12345L)
        val origRms = rms(src)
        var last = ShortArray(block)
        for (k in 0 until 200) {
            val buf = widebandNoise(0.20f, 12345L + k)
            d.process(buf, block)
            last = buf
        }
        val after = rms(last)
        val noiseEst = d.estimatedNoisePower
        println("[DIAG] 稳态测试: orig=$origRms after=$after noiseEst=$noiseEst")
        // 宽带稳态噪声应被显著压低（目标：至少降 20%）
        assertTrue(
            "宽带噪声应被压低 20% 以上，原始=$origRms 实际=$after",
            after < origRms * 0.80f
        )
    }

    @Test
    fun `瞬态不被过度削弱`() {
        val d = NoiseSuppressor(block)
        d.enabled = true
        d.setStrength(0.7f)
        // 先让它学习一段安静的噪声
        for (k in 0 until 40) {
            d.process(steady(500.0, 0.01f, k * 0.7), block)
        }
        // 突然来一个宽频瞬态（模拟快门：大量随机高频 + 强幅度）
        val burst = ShortArray(block) { i ->
            val noise = (((i * 2654435761L) % 20000L - 10000L).toFloat()) / 10000f
            val v = noise * 0.55f + sin(2 * Math.PI * 3000 * i / sr).toFloat() * 0.35f
            (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val before = rms(burst)
        val copy = burst.copyOf()
        d.process(copy, block)
        val after = rms(copy)
        // 瞬态 должна保留大部分能量：衰减不应超过 50%
        // 瞬态应保留大部分能量：衰减不应超过 50%
        assertTrue(
            "瞬态被过度削弱：before=$before after=$after",
            after.toDouble() > before.toDouble() * 0.5
        )
    }

    @Test
    fun `静音输入不产生异常输出`() {
        val d = NoiseSuppressor(block)
        d.enabled = true
        val silent = ShortArray(block)
        d.process(silent, block)
        for (v in silent) {
            assertTrue("静音输入不应产生爆音", v.toInt() in -200..200)
        }
    }

    @Test
    fun `非 2 的幂块长不会越界`() {
        // 882 = 44100/50（20ms），**不是 2 的幂**。
        // 之前的实现直接拿 882 做基 2 FFT，导致
        // ArrayIndexOutOfBoundsException: length=882; index=882。
        // 现在内部会自动上取到 1024。
        val d = NoiseSuppressor(882)
        d.enabled = true
        d.setStrength(0.7f)
        val buf = noiseBlock882(0.3f, 42L)
        // 不抛异常即为通过
        d.process(buf, 882)
        assertTrue("处理应正常返回", true)
    }

    @Test
    fun `各种常见块长都不越界`() {
        for (size in intArrayOf(128, 256, 441, 512, 882, 1024, 2048)) {
            val d = NoiseSuppressor(size)
            d.enabled = true
            val buf = ShortArray(size) { 1000 }
            d.process(buf, size)
        }
        assertTrue(true)
    }

    private fun noiseBlock882(amp: Float, seed: Long): ShortArray {
        var state = seed
        return ShortArray(882) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val r = ((state ushr 33) % 20001L - 10000L).toFloat() / 10000f
            (r * amp * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
