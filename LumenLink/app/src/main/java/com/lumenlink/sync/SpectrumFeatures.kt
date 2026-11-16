package com.lumenlink.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轻量频谱分析：为快门声识别提供频带特征。
 *
 * 为什么不用现成的 FFT 库：只需要 8 个频带的能量，用**一组二阶 IIR 带通滤波器**
 * 就够了，代价是 O(bands × N) 的简单乘加，比每次做 1024 点 FFT 轻得多，
 * 而且能直接接在实时音频流上逐块处理（不需要攒满一帧）。
 *
 * 频带划分依据机械快门声的物理特性：
 *  - 快门是金属叶片/幕帘的碰撞，属**宽带瞬态**，能量在 1k~8kHz 都显著；
 *  - 人声基频 85~255Hz、共振峰 500~3kHz，且**低频占比高**；
 *  - 空调/风扇是持续低频噪声，瞬态特征弱（会被包络判据排除）。
 */
class SpectrumFeatures(private val sampleRate: Int = 44_100) {

    /** 频带中心频率（Hz）。 */
    private val bandCenters = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

    private val bands = bandCenters.size
    private val filters = Array(bands) { BiquadBandpass() }

    /** 每个频带的能量（已归一化到 RMS 尺度）。 */
    private val energy = FloatArray(bands)

    init {
        for (i in 0 until bands) {
            // Q 值取 1.4：带宽约为中心的 0.7 倍，相邻频带刚好不重叠太多
            filters[i].configure(bandCenters[i].toDouble(), 1.4, sampleRate.toDouble())
        }
    }

    /**
     * 处理一块 PCM16 数据，更新内部各频带能量。
     * @return 频带能量的只读视图（复用同一数组，注意不要跨块持有）
     */
    fun process(pcm: ShortArray, length: Int): FloatArray {
        for (i in 0 until bands) energy[i] = 0f
        if (length <= 0) return energy

        val tmp = FloatArray(length)
        val out = FloatArray(length)
        for (b in 0 until bands) {
            val f = filters[b]
            var acc = 0.0
            for (n in 0 until length) tmp[n] = pcm[n] / 32768f
            f.process(tmp, out, length)
            for (n in 0 until length) acc += out[n].toDouble() * out[n]
            energy[b] = sqrt(acc / length).toFloat()
        }
        return energy
    }

    /**
     * 把频带能量转成"频谱质心"（Hz）——能量重心的位置。
     * 快门（宽带）质心偏高；人声（低频重）质心偏低。
     */
    fun centroidHz(): Float {
        var num = 0.0
        var den = 0.0
        for (i in 0 until bands) {
            num += energy[i].toDouble() * bandCenters[i]
            den += energy[i].toDouble()
        }
        return if (den < 1e-9) 0f else (num / den).toFloat()
    }

    /** 高频（≥3kHz）能量占总能量的比例。快门典型 > 0.35，人声典型 < 0.15。 */
    fun highBandRatio(): Float {
        var low = 0.0
        var high = 0.0
        for (i in 0 until bands) {
            val e = energy[i].toDouble()
            if (bandCenters[i] >= 3000) high += e else low += e
        }
        val total = low + high
        return if (total < 1e-9) 0f else (high / total).toFloat()
    }

    /**
     * 频谱平坦度（spectral flatness），0..1。
     *
     * 几何均值 / 算术均值：
     *  - 接近 1 → 白噪声式宽带（快门、摩擦声）
     *  - 接近 0 → 强谐波结构（人声、音乐、鸟叫）
     * 这是区分"瞬态"与"有音高的声音"最有效的单一指标。
     */
    fun flatness(): Float {
        var logSum = 0.0
        var sum = 0.0
        var n = 0
        for (i in 0 until bands) {
            val e = energy[i].toDouble() + 1e-10
            logSum += ln(e)
            sum += e
            n++
        }
        if (n == 0 || sum < 1e-9) return 0f
        val geo = kotlin.math.exp(logSum / n)
        val arith = sum / n
        return (geo / arith).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 低频（≤500Hz）能量占比。人声能量集中在这里。
     */
    fun lowBandRatio(): Float {
        var low = 0.0
        var total = 0.0
        for (i in 0 until bands) {
            val e = energy[i].toDouble()
            total += e
            if (bandCenters[i] <= 500) low += e
        }
        return if (total < 1e-9) 0f else (low / total).toFloat()
    }

    fun bandEnergies(): FloatArray = energy

    /**
     * 生成 **24 带对数频谱向量**，供音色模板匹配使用。
     *
     * 与 [bandEnergies]（8 个粗带，用于快速判据）不同，这个是"音色指纹"：
     *  - 24 个对数分布频带（125Hz~12kHz），分辨率足够区分不同材质的碰撞声
     *  - 用 FFT 而不是滤波器组，能同时看到所有频带，不会因 Q 值互相污染
     *
     * @return 长度 = [TimbreTemplate.BANDS] 的原始能量向量（未归一化）
     */
    fun timberVector(pcm: ShortArray, length: Int): FloatArray {
        val out = FloatArray(TimbreTemplate.BANDS)
        if (length <= 0) return out
        fftAnalyzer.process(pcm, length)
        val spec = fftAnalyzer.magnitudes()      // 长度 fftSize/2+1
        val binHz = fftAnalyzer.binHz()
        val centers = timbreBands

        for (b in 0 until TimbreTemplate.BANDS) {
            // 每个对数频带覆盖 [lo, hi]，累加其中的 FFT bin 能量
            val center = centers[b]
            val lo = if (b == 0) center * 0.75f else (centers[b - 1] + center) / 2f
            val hi = if (b == TimbreTemplate.BANDS - 1) center * 1.33f
            else (center + centers[b + 1]) / 2f
            val iLo = (lo / binHz).toInt().coerceIn(1, spec.size - 1)
            val iHi = (hi / binHz).toInt().coerceIn(iLo + 1, spec.size)
            var acc = 0.0
            for (i in iLo until iHi) acc += spec[i].toDouble() * spec[i]
            // 除以带宽，避免高频带宽大导致数值天然偏大
            out[b] = kotlin.math.sqrt(acc / (iHi - iLo).coerceAtLeast(1)).toFloat()
        }
        return out
    }

    private val fftAnalyzer = SpectrumFft()
    private val timbreBands = TimbreTemplate.defaultBands()

    /** 频带中心频率，供 UI/日志标注。 */
    fun bandCenterList(): IntArray = bandCenters

    /**
     * 生成一份完整快照，用于"频谱采集"功能。
     *
     * 这是校准的关键输出：把 8 个原始频带能量 + 四个派生指标一次性导出，
     * 用户按快门/说话各采一条，就能在**同一条处理链路**上对比。
     * 靠截图换算做不到这点（刻度、dB 轴、平滑都可能不同）。
     */
    fun snapshot(rms: Float, peak: Float, threshold: Float): String {
        val sb = StringBuilder()
        sb.append("rms=%.5f peak=%.5f thr=%.5f | ".format(rms, peak, threshold))
        val centers = bandCenters
        val maxE = (0 until bands).maxOfOrNull { energy[it] } ?: 0f
        sb.append("bands[")
        for (i in 0 until bands) {
            if (i > 0) sb.append(' ')
            // 同时给绝对值与相对最强频带的比率，便于判断形状
            val rel = if (maxE > 1e-9f) energy[i] / maxE else 0f
            sb.append("%d:%.3f(%.2f)".format(centers[i], energy[i], rel))
        }
        sb.append("] ")
        sb.append("flat=%.3f high=%.3f low=%.3f cent=%.0f"
            .format(flatness(), highBandRatio(), lowBandRatio(), centroidHz()))
        return sb.toString()
    }
}

/**
 * 二阶带通滤波器（RBJ Audio EQ Cookbook 的 BPF，constant 0 dB peak gain）。
 * 逐样本处理，状态常驻，可跨块连续使用。
 */
private class BiquadBandpass {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(centerHz: Double, q: Double, sampleRate: Double) {
        val w0 = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosw0 = cos(w0)
        val a0 = 1.0 + alpha

        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = (-2.0 * cosw0) / a0
        a2 = (1.0 - alpha) / a0

        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    fun process(input: FloatArray, output: FloatArray, length: Int) {
        for (n in 0 until length) {
            val x0 = input[n].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            output[n] = y0.toFloat()
        }
    }
}
