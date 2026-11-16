package com.lumenlink.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色模板测试：验证能区分"同类声音"和"不同类声音"。
 *
 * 这是替代"音量阈值"方案的核心验证：
 * 同类（都是快门）应当通过，异类（说话/关门）应当被拒绝，
 * 而且**与绝对响度无关**——很响的说话声也必须被拒。
 */
class TimbreTemplateTest {

    private val bands = TimbreTemplate.BANDS

    /** 构造一个"类快门"的宽带形状：各频带能量接近，略微偏高。 */
    private fun shutterLike(scale: Float, jitter: Float = 0.05f, seed: Int = 1): FloatArray {
        var s = seed
        return FloatArray(bands) { i ->
            s = s * 1103515245 + 12345
            val j = ((s ushr 16) % 1000) / 1000f * jitter * 2f - jitter
            // 高频略强（金属碰撞特征）
            val base = 0.5f + i.toFloat() / bands * 0.5f
            (base + j) * scale
        }
    }

    /** 构造一个"类人声"的形状：能量集中在低频。 */
    private fun voiceLike(scale: Float, seed: Int = 7): FloatArray {
        var s = seed
        return FloatArray(bands) { i ->
            s = s * 1103515245 + 12345
            val j = ((s ushr 16) % 1000) / 1000f * 0.1f
            // 低频强、高频衰减
            val base = kotlin.math.exp(-i / 4.0).toFloat()
            (base + j) * scale
        }
    }

    @Test
    fun `训练后同类声音通过`() {
        val t = TimbreTemplate()
        val samples = (1..6).map { shutterLike(1.0f, seed = it) }
        t.train(samples, List(samples.size) { 40f })
        assertTrue("应完成训练", t.isTrained)
        assertTrue("同类样本应命中", t.matches(shutterLike(1.0f, seed = 99)))
    }

    @Test
    fun `训练后异类声音被拒绝`() {
        val t = TimbreTemplate()
        val samples = (1..6).map { shutterLike(1.0f, seed = it) }
        t.train(samples, List(samples.size) { 40f })
        assertFalse("人声形状不应命中", t.matches(voiceLike(1.0f)))
    }

    @Test
    fun `很响的异类声音仍被拒绝`() {
        val t = TimbreTemplate()
        val samples = (1..6).map { shutterLike(1.0f, seed = it) }
        t.train(samples, List(samples.size) { 40f })
        // 音量放大 100 倍，形状不变
        assertFalse("响度不该影响音色判定", t.matches(voiceLike(100f)))
    }

    @Test
    fun `很轻的同类声音仍被接受`() {
        val t = TimbreTemplate()
        val samples = (1..6).map { shutterLike(1.0f, seed = it) }
        t.train(samples, List(samples.size) { 40f })
        // 音量缩小 100 倍，形状不变
        assertTrue("响度不该影响音色判定", t.matches(shutterLike(0.01f, seed = 42)))
    }

    @Test
    fun `样本一致时门限较高`() {
        val t = TimbreTemplate()
        val samples = (1..8).map { shutterLike(1.0f, jitter = 0.01f, seed = it) }
        t.train(samples, emptyList())
        assertTrue("一致性高时门限应偏严", t.similarityGate >= 0.85f)
    }

    @Test
    fun `未训练时匹配一律返回否`() {
        val t = TimbreTemplate()
        assertFalse("未训练不应命中", t.matches(shutterLike(1f)))
        assertTrue("相似度应为 -1", t.similarityOf(shutterLike(1f)) < 0f)
    }

    @Test
    fun `空样本训练不改变状态`() {
        val t = TimbreTemplate()
        t.train(emptyList(), emptyList())
        assertFalse("空样本不应训练成功", t.isTrained)
    }

    @Test
    fun `相似度对整体缩放不变`() {
        val t = TimbreTemplate()
        val samples = (1..6).map { shutterLike(1.0f, seed = it) }
        t.train(samples, emptyList())
        val a = t.similarityOf(shutterLike(1.0f, seed = 5))
        val b = t.similarityOf(shutterLike(1000f, seed = 5))
        assertTrue(
            "整体缩放不应改变相似度：$a vs $b",
            kotlin.math.abs(a - b) < 0.01f
        )
    }
}
