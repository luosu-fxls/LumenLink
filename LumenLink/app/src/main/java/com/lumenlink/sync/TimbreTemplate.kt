package com.lumenlink.sync

import kotlin.math.sqrt

/**
 * 音色模板：用真实快门声的频谱形状训练出来的判据。
 *
 * ## 为什么不能再靠"阈值"
 *
 * 之前的一键校准是"取样本电平的中位数和最大值取中点当阈值"，
 * 本质是**猜一个音量门槛**。这解决不了误触发：
 * 说话、关门、放东西的响度都可能超过快门，但**音色完全不同**。
 *
 * ## 音色 = 频谱形状
 *
 * 本类把一段声音表示成 **24 个频带的对数能量向量**，然后：
 *   1. 归一化掉整体响度（只保留形状）
 *   2. 与快门模板做**余弦相似度**
 *   3. 相似度 ≥ 门限才算快门
 *
 * 这样"很响的说话声"会因为形状不符被拒绝，
 * "很轻的快门声"只要形状对就能通过 —— 这才是识别音色。
 *
 * ## 额外的时间特征
 *
 * 单帧频谱不足以区分快门和拍手（都是宽带瞬态）。
 * 所以模板里还记录了**持续时间**：快门通常 20~60ms，
 * 拍手更短更尖，关门更长更闷。
 */
class TimbreTemplate(
    /** 24 个频带的中心频率（Hz），对数分布覆盖 125Hz~12kHz。 */
    val bandCenters: FloatArray = defaultBands(),
) {

    companion object {
        /** 频带数量。24 带比 8 带有足够分辨率描述"音色"。 */
        const val BANDS = 24

        /**
         * 对数分布的中心频率，覆盖 125Hz ~ 12kHz。
         *
         * 用对数是因为人耳和"音色"本质上是对数感知的，
         * 也正好让低频（人声基频）和高频（金属碰撞）都有足够分辨率。
         */
        fun defaultBands(): FloatArray {
            val lo = 125f
            val hi = 12_000f
            return FloatArray(BANDS) { i ->
                lo * Math.pow((hi / lo).toDouble(), i.toDouble() / (BANDS - 1)).toFloat()
            }
        }
    }

    /** 快门模板：归一化后的 24 带能量向量（长度 1）。 */
    @Volatile
    var shutterShape: FloatArray = FloatArray(0)
        private set

    /** 快门持续时间中位数（毫秒）。 */
    @Volatile
    var shutterDurationMs: Float = 0f
        private set

    /** 训练样本数。 */
    @Volatile
    var trainedCount: Int = 0
        private set

    /** 相似度门限：低于此值不算快门。由训练时样本间的一致程度自动决定。 */
    @Volatile
    var similarityGate: Float = 0.80f
        private set

    /** 训练时样本内部的最低相似度，用于告诉用户"你的快门声是否稳定"。 */
    @Volatile
    var minSelfSimilarity: Float = 1f
        private set

    val isTrained: Boolean get() = shutterShape.isNotEmpty() && trainedCount > 0

    /**
     * 用若干条快门样本训练模板。
     *
     * @param shapes 每条样本的 24 带原始能量（未归一化）
     * @param durations 每条样本的持续时间（ms），可为空
     * @return 训练报告
     */
    fun train(shapes: List<FloatArray>, durations: List<Float>): String {
        val valid = shapes.filter { it.size == BANDS && it.any { e -> e > 1e-9f } }
        if (valid.isEmpty()) {
            return "训练失败：没有有效的频谱样本。"
        }

        // 归一化每条样本（去掉响度，只留形状）
        val normed = valid.map { normalize(it) }

        // 模板取逐带中位数：中位数比均值更抗离群（防止误采的非快门声把模板带偏）
        val template = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val vals = normed.map { it[b] }.sorted()
            template[b] = vals[vals.size / 2]
        }
        val templateNormed = normalize(template)
        shutterShape = templateNormed

        // 计算样本与模板的一致性，据此定门限
        val sims = normed.map { cosine(it, templateNormed) }
        minSelfSimilarity = sims.minOrNull() ?: 1f
        val avgSim = sims.average().toFloat()

        // 门限 = 样本一致性的低位，但不低于 0.70（否则太松）
        // 样本越一致，门限越接近一致性下限；越分散，门限越保守
        similarityGate = (minSelfSimilarity - 0.05f).coerceIn(0.70f, 0.95f)

        trainedCount = normed.size

        if (durations.isNotEmpty()) {
            val sorted = durations.filter { it > 0f }.sorted()
            shutterDurationMs = if (sorted.isEmpty()) 0f else sorted[sorted.size / 2]
        }

        return buildString {
            appendLine("=== 音色模板训练完成 ===")
            appendLine("有效样本: ${normed.size} 条")
            appendLine("样本一致性: 平均 %.2f / 最低 %.2f".format(avgSim, minSelfSimilarity))
            appendLine("相似度门限: %.2f".format(similarityGate))
            if (shutterDurationMs > 0f) {
                appendLine("快门持续时间: %.0f ms".format(shutterDurationMs))
            }
            appendLine()
            append("频谱形状（24 带，相对强度）: ")
            // 用字符画一个粗略形状，便于肉眼判断模板是否合理
            for (b in 0 until BANDS) {
                append(barChar(templateNormed[b]))
            }
            appendLine()
            appendLine("低频←                                        →高频")
            appendLine()
            if (minSelfSimilarity < 0.75f) {
                appendLine("⚠ 样本之间差异较大，可能采到了非快门声（说话/环境噪声）。")
                appendLine("  建议重新采样，每次只在按快门时出声。")
            } else {
                appendLine("✓ 样本一致，可以用于识别。")
            }
        }
    }

    /**
     * 判断一个频谱是否符合快门音色。
     *
     * @return 相似度（0..1）；未训练时返回 -1
     */
    fun similarityOf(shape: FloatArray): Float {
        if (!isTrained || shape.size != BANDS) return -1f
        if (shape.all { it <= 1e-9f }) return 0f
        return cosine(normalize(shape), shutterShape)
    }

    /** 命中判定。 */
    fun matches(shape: FloatArray): Boolean {
        if (!isTrained) return false
        return similarityOf(shape) >= similarityGate
    }

    fun reset() {
        shutterShape = FloatArray(0)
        shutterDurationMs = 0f
        trainedCount = 0
        similarityGate = 0.80f
        minSelfSimilarity = 1f
    }

    /** 导出模板，便于跨会话持久化或人工比对。 */
    fun export(): String {
        if (!isTrained) return "(未训练)"
        val sb = StringBuilder("gate=%.3f dur=%.0fms bands=".format(similarityGate, shutterDurationMs))
        for (b in 0 until BANDS) {
            if (b > 0) sb.append(',')
            sb.append("%.4f".format(shutterShape[b]))
        }
        return sb.toString()
    }

    // ---------------- 数学工具 ----------------

    /** 归一化到单位长度；全零返回原数组。 */
    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-9f) return FloatArray(v.size)
        return FloatArray(v.size) { v[it] / norm }
    }

    /** 余弦相似度，输入必须已归一化。 */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i].toDouble() * b[i]
        return dot.toFloat().coerceIn(0f, 1f)
    }

    private fun barChar(v: Float): Char = when {
        v < 0.06f -> '_'
        v < 0.15f -> '▁'
        v < 0.25f -> '▂'
        v < 0.35f -> '▃'
        v < 0.45f -> '▄'
        v < 0.55f -> '▅'
        v < 0.65f -> '▆'
        v < 0.80f -> '▇'
        else -> '█'
    }
}
