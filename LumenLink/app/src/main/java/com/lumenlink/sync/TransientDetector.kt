package com.lumenlink.sync

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 快门声瞬态检测器（自适应双门限 + 上升沿）。
 *
 * ## 为什么不用单一绝对阈值
 *
 * 快门声的强度随距离、机身、环境剧烈变化（20cm 处可能有 -20dBFS，1m 处只有 -45dBFS），
 * 固定阈值必然"要么一直响，要么一直不响"。
 *
 * ## 本算法的三层判据
 *
 * 1. **快速包络（fast）**：跟随当前声音，时间常数极短
 * 2. **慢速包络（slow）**：当前环境的"平均响度"，作为参考
 * 3. **上升沿判据**：fast 突然冲到 slow 的数倍以上，说明发生了瞬态
 *
 * 快门声的特征正是"极短（10~40ms）且远高于背景的上升沿"，
 * 而人声/音乐是持续的能量，fast/slow 比值不会突增。
 *
 * ## 与频谱判据的关系
 *
 * 上升沿负责"发现事件"，频谱负责"确认是不是快门"（排除人声、音乐）。
 * 两者串联，误触发率远低于任一单独使用。
 */
class TransientDetector(
    private val sampleRate: Int = 44_100,
) {

    /** 检测结果。 */
    data class Hit(
        /** 该块 RMS */
        val rms: Float,
        /** fast 包络值 */
        val fast: Float,
        /** slow 包络值（参考电平） */
        val slow: Float,
        /** fast / slow 比值 */
        val riseRatio: Float,
        /** 该块峰值 */
        val peak: Float,
        /** 谱特征摘要文本，未启用频谱时为 null */
        val spectrumInfo: String?,
        /** 谱平坦度 */
        val flatness: Float = 0f,
        /** 高频占比 */
        val highRatio: Float = 0f,
        /** 低频占比 */
        val lowRatio: Float = 0f,
        /** 质心 Hz */
        val centroidHz: Float = 0f,
        /** 与快门音色模板的相似度（0..1），未训练时为 -1。 */
        val timbreSimilarity: Float = -1f,
    )

    // ---------------- 包络 ----------------

    /** 快速包络时间常数（秒）。越小越灵敏，但越容易被噪声触发。 */
    private var fastTau = 0.004f

    /** 慢速包络时间常数（秒）。决定"多久算背景"。 */
    private var slowTau = 0.250f

    private var fast = 0f
    private var slow = 1e-4f

    /** 预热块计数：前若干块只建立 slow 参考，不做任何判定。 */
    private var warmingUp = 0

    /**
     * 预热块数。20ms/块 × 15 块 = 300ms。
     *
     * 必须足够长：slow 需要用这段时间收敛到真实环境电平，
     * 否则启动初期 riseRatio 会虚高，导致一开机就误触发。
     */
    private val WARMUP_BLOCKS = 15

    /** 上升沿触发倍数。越大越保守。 */
    var riseGate = 3.0f

    /** 绝对下限：低于这个 RMS 一律不触发，避免完全安静时被微小噪声触发。 */
    var absoluteFloor = 0.0006f

    /** 最小间隔（毫秒），防止一次瞬态重复触发。 */
    var refractoryMs = 120L

    private var lastHitNanos = 0L

    /** 环境噪声底（安静时的 slow 值），用于 UI 显示和"太安静/太吵"判断。 */
    @Volatile
    var noiseFloor: Float = 0f
        private set

    /** 最近一次被拦截的块，用于诊断为什么没触发。 */
    @Volatile
    var lastRejectReason: String = ""

    /** 最近一块的 RMS / 峰值 / 频谱，无论是否命中都更新（供 UI 实时显示）。 */
    @Volatile
    var lastRms: Float = 0f
        private set

    @Volatile
    var lastPeak: Float = 0f
        private set

    @Volatile
    var lastFlatness: Float = 0f
        private set

    @Volatile
    var lastHighRatio: Float = 0f
        private set

    @Volatile
    var lastLowRatio: Float = 0f
        private set

    /** 最近一块的 24 带音色向量（供采样训练用）。 */
    @Volatile
    var lastTimbreVector: FloatArray = FloatArray(0)
        private set

    /** 最近一次判定的音色相似度（-1 表示未启用模板）。 */
    @Volatile
    var lastSimilarity: Float = -1f
        private set

    /** 用灵敏度 0..1 换算内部参数。越高越容易触发。 */
    fun applySensitivity(s: Float) {
        val v = s.coerceIn(0f, 1f)
        // 高灵敏：上升沿门槛低、绝对下限低
        riseGate = 4.5f - v * 3.2f          // 1.3 ~ 4.5
        absoluteFloor = 0.0020f - v * 0.0017f // 0.0003 ~ 0.0020
        // 高灵敏时包络也更快，抓得住更软的快门
        fastTau = 0.006f - v * 0.0035f       // 0.0025 ~ 0.006
        slowTau = 0.320f - v * 0.140f        // 0.180 ~ 0.320
    }

    fun reset() {
        fast = 0f
        slow = 1e-4f
        warmingUp = 0
        lastHitNanos = 0L
        lastRejectReason = ""
    }

    /**
     * 处理一块音频。
     *
     * @param buf 采样数据（PCM 16bit）
     * @param n   有效长度
     * @param prevSample 上一块最后一个采样（用于一阶高通连续性）
     * @param nowNanos 本块开始时间
     * @param spectrum 频谱分析器（可选，传入则做频谱确认）
     * @return 命中则返回 [Hit]，否则 null
     */
    fun process(
        buf: ShortArray,
        n: Int,
        prevSample: FloatArray,
        nowNanos: Long,
        spectrum: SpectrumFeatures?,
        spectrumEnabled: Boolean,
        spectrumGates: SpectrumGates,
        /** 音色模板；已训练时优先用它判定，未训练则退回旧的四指标判据。 */
        timbre: TimbreTemplate? = null,
    ): Hit? {
        if (n <= 0) return null

        // ---- 计算本块 RMS 与峰值（一阶高通，去掉直流和低频嗡声）----
        var sum = 0.0
        var peak = 0f
        var prev = prevSample[0]
        for (i in 0 until n) {
            val s = buf[i] / 32768f
            val hp = s - prev
            prev = s
            sum += hp.toDouble() * hp
            if (abs(hp) > peak) peak = abs(hp)
        }
        prevSample[0] = prev
        val rms = sqrt(sum / n).toFloat()
        lastRms = rms
        lastPeak = peak

        // 频谱始终推进，保证滤波器状态连续（切换开关时不留延迟）
        if (spectrum != null) {
            spectrum.process(buf, n)
            lastFlatness = spectrum.flatness()
            lastHighRatio = spectrum.highBandRatio()
            lastLowRatio = spectrum.lowBandRatio()
        }

        val blockSec = n.toFloat() / sampleRate

        // ---- 包络更新 ----
        // fast 上升瞬时、下降按时间常数
        val fastAlpha = 1f - kotlin.math.exp(-blockSec / fastTau)
        fast = if (rms > fast) rms else fast + (rms - fast) * fastAlpha

        // slow 是"环境平均响度"，必须**始终跟随**，不能只在非瞬态时更新。
        //
        // 早先的写法（只在非瞬态时更新）有一个致命缺陷：
        // 一旦因为启动初期 slow 太小而误判为瞬态，slow 就再也不更新，
        // 永远卡在极低值 → riseRatio 永远巨大 → 持续误触发，且无法自愈。
        // 这正是"太灵敏、停不下来"的原因。
        //
        // 现在改为始终指数跟随。瞬态（1~3 块）对 τ≈0.25s 的 slow 影响很小，
        // 不足以让它跟上来，所以仍然能检出上升沿。
        if (warmingUp < WARMUP_BLOCKS) {
            // 预热期：直接把 slow 拉到当前电平，避免启动瞬间的比值爆炸
            warmingUp++
            slow = rms.coerceAtLeast(1e-4f)
        } else {
            val slowAlpha = 1f - kotlin.math.exp(-blockSec / slowTau)
            slow += (rms - slow) * slowAlpha
        }
        if (slow < 1e-4f) slow = 1e-4f
        noiseFloor = slow

        val riseRatio = fast / slow

        // 预热期内不做任何判定：此时 slow 还没建立起可信的参考电平
        if (warmingUp < WARMUP_BLOCKS) {
            lastRejectReason = "预热中 ($warmingUp/$WARMUP_BLOCKS)"
            return null
        }

        // ---- 判据 1：绝对下限 ----
        if (rms < absoluteFloor) {
            lastRejectReason = "低于绝对下限 (rms=%.5f < %.5f)".format(rms, absoluteFloor)
            return null
        }

        // ---- 判据 2：上升沿 ----
        if (riseRatio < riseGate) {
            lastRejectReason = "上升沿不足 (%.1f < %.1f)".format(riseRatio, riseGate)
            return null
        }

        // ---- 判据 3：最小间隔 ----
        val sinceMs = if (lastHitNanos == 0L) Long.MAX_VALUE else (nowNanos - lastHitNanos) / 1_000_000L
        if (sinceMs < refractoryMs) {
            lastRejectReason = "间隔过短 (${sinceMs}ms < ${refractoryMs}ms)"
            return null
        }

        // ---- 判据 4：音色（优先）或频谱形状（退回）----
        var info: String? = null
        val flat = lastFlatness
        val high = lastHighRatio
        val low = lastLowRatio
        var cent = 0f
        var similarity = -1f

        if (spectrum != null) {
            cent = spectrum.centroidHz()
            info = spectrum.snapshot(rms, peak, slow)

            // 音色模板已训练 → 用模板匹配，这才是"识别音色"
            if (timbre != null && timbre.isTrained) {
                lastTimbreVector = spectrum.timberVector(buf, n)
                similarity = timbre.similarityOf(lastTimbreVector)
                lastSimilarity = similarity
                if (similarity < timbre.similarityGate) {
                    lastRejectReason =
                        "音色不符 (相似度 %.2f < %.2f)".format(similarity, timbre.similarityGate)
                    return null
                }
            } else if (spectrumEnabled) {
                // 未训练时退回四指标判据（只能粗筛，容易误触，故引导用户训练）
                val flatOk = flat >= spectrumGates.flat
                val highOk = high >= spectrumGates.high
                val lowOk = low <= spectrumGates.low
                val centOk = cent >= spectrumGates.centroid
                if (!(flatOk && highOk && lowOk && centOk)) {
                    lastRejectReason = "频谱不符 (flat=%.2f 高=%.2f 低=%.2f 质心=%.0f)".format(
                        flat, high, low, cent
                    )
                    return null
                }
            }
        }

        lastHitNanos = nowNanos
        lastRejectReason = ""
        return Hit(rms, fast, slow, riseRatio, peak, info, flat, high, low, cent, similarity)
    }

    /** 频谱门限。 */
    data class SpectrumGates(
        val flat: Float = 0.18f,
        val high: Float = 0.28f,
        val low: Float = 0.55f,
        val centroid: Float = 1200f,
    )

    companion object {
        /**
         * 根据灵敏度生成一组频谱门限。
         *
         * 灵敏度越高，门限越松（宁可误触不可漏），反之越严。
         */
        fun spectrumGatesFor(sensitivity: Float): SpectrumGates {
            val s = sensitivity.coerceIn(0f, 1f)
            return SpectrumGates(
                flat = 0.40f * (1f - s * 0.75f) + 0.06f,   // 0.46 ~ 0.16
                high = 0.45f * (1f - s * 0.70f) + 0.05f,   // 0.50 ~ 0.185
                low = 0.35f + s * 0.55f,                    // 0.35 ~ 0.90
                centroid = 2200f - s * 1400f,               // 2200 ~ 800
            )
        }
    }
}
