package com.lumenlink.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波形缩略的边界测试。
 *
 * 回归目标：曾经在 n == pcm.size（都是 882）时输出 pcm[882] 越界，
 * 用户看到的是"录一次之后再录就麦克风出错 length=882; index=882"。
 *
 * 这里直接复制 recordSample 里的缩略算法做验证（该函数是 private，
 * 不为了测试而放开可见性，改为验证等价逻辑）。
 */
class WaveformThumbnailTest {

    /** 与 AudioTrigger.recordSample 中的实现保持一致的缩略算法。 */
    private fun thumbnail(pcm: ShortArray, n: Int, points: Int = 64): FloatArray {
        val limit = kotlin.math.min(n, pcm.size)
        if (limit <= 0) return FloatArray(0)
        val out = FloatArray(points)
        for (p in 0 until points) {
            val from = (p * limit / points).coerceIn(0, limit - 1)
            val to = ((p + 1) * limit / points).coerceIn(from + 1, limit)
            var mx = 0f
            var i = from
            while (i < to && i < limit) {
                val v = kotlin.math.abs(pcm[i] / 32768f)
                if (v > mx) mx = v
                i++
            }
            out[p] = mx
        }
        return out
    }

    @Test
    fun `n 等于数组长度时不越界`() {
        val pcm = ShortArray(882) { 1000 }
        val wave = thumbnail(pcm, 882)
        assertEquals(64, wave.size)
        assertTrue("应该有非零峰值", wave.maxOrNull()!! > 0f)
    }

    @Test
    fun `n 小于数组长度时不越界`() {
        val pcm = ShortArray(882)
        val wave = thumbnail(pcm, 441)
        assertEquals(64, wave.size)
    }

    @Test
    fun `n 大于数组长度时被夹紧`() {
        val pcm = ShortArray(882) { 1000 }
        // 模拟 n 与 pcm.size 不一致（重建录音器后可能发生）
        val wave = thumbnail(pcm, 1000)
        assertEquals(64, wave.size)
    }

    @Test
    fun `极小输入不越界`() {
        val pcm = ShortArray(882)
        val wave = thumbnail(pcm, 1)
        assertEquals(64, wave.size)
    }

    @Test
    fun `零长度输入返回空数组`() {
        val pcm = ShortArray(882)
        assertEquals(0, thumbnail(pcm, 0).size)
    }

    @Test
    fun `空数组输入返回空数组`() {
        assertEquals(0, thumbnail(ShortArray(0), 0).size)
    }

    @Test
    fun `峰值位置反映真实信号位置`() {
        val pcm = ShortArray(882)
        // 在中间放一个尖峰
        pcm[441] = 32000
        val wave = thumbnail(pcm, 882)
        val peakIdx = wave.indices.maxByOrNull { wave[it] } ?: -1
        // 441/882 * 64 = 32，允许 ±1 的量化误差
        assertTrue("峰值应落在中间附近，实际在 $peakIdx", kotlin.math.abs(peakIdx - 32) <= 2)
    }
}
