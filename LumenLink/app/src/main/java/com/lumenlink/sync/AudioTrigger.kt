package com.lumenlink.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.lumenlink.core.Logx
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 快门声触发 —— **旁听模式的核心**。
 *
 * 设计前提（很重要）：
 *   这个类**完全不接触相机**。它只听麦克风，不动 PTP、不改机身参数、不锁定相机。
 *   用户自己在相机上调快门/光圈/ISO，App 只负责"听到快门就做出反应"。
 *
 * 为什么这条路才是对的：
 *   - 通过 USB/Wi-Fi 连相机，相机通常会进入受控状态，物理操作被接管，
 *     用户想要的"自己调快门"反而做不到了；
 *   - 音频触发与相机型号、协议、固件版本全部无关，也不需要 root。
 *
 * 算法（单线程、无第三方依赖）：
 *  1. 一阶差分高通：滤掉风噪/空调/直流偏置等低频成分；
 *  2. 20ms 块 RMS 作为包络；
 *  3. 快攻慢放跟踪噪声底，阈值 = max(绝对门限, 噪声底 × 倍率)；
 *  4. 过阈即判定瞬态，进入不应期防止一次快门被拆成多次；
 *  5. 用"频谱倾斜"进一步排除人声/拍手：机械快门是宽带瞬态，
 *     能量分布比人声更均匀，低频占比更低。
 */
class AudioTrigger(private val context: Context) {

    interface Listener {
        /** 检测到瞬态。@param rms 峰值 RMS（0..1），@param atNanos 起音时刻 */
        fun onTransient(rms: Float, atNanos: Long)
        fun onError(t: Throwable)

        /**
         * 实时电平。
         * @param rms 当前块 RMS
         * @param threshold 当前生效阈值
         * @param peak 当前块峰值
         *
         * 把阈值一起回传，UI 才能画出"离触发还差多少"——
         * 否则用户只能看到"不涨"，无从判断是差一点点还是差很远。
         */
        fun onLevel(rms: Float, threshold: Float, peak: Float, spectrumInfo: String)

        /** 采样阶段变化：[capturing]=true 表示已稳定、可以按快门了。默认空实现。 */
        fun onSamplePhaseChanged(capturing: Boolean) {}
    }

    /** 0..1，越大越灵敏。默认 0.75（偏灵敏，因为实测默认 0.5 太钝）。 */
    @Volatile
    var sensitivity: Float = 0.75f

    /** 绝对门限下限，防止在极安静环境下被呼吸/衣服摩擦触发。 */
    @Volatile
    var absoluteFloor: Float = 0.010f

    /**
     * 低频能量占比上限（0..1）。保留作为兼容/兜底；真正的判据已升级为频谱形状。
     */
    @Volatile
    var maxLowFreqRatio: Float = 0.80f

    /**
     * 频谱平坦度门槛：低于此值认为是"有音高的声音"（人声/音乐/鸟叫），不是快门。
     * 机械快门典型 0.3~0.6；人声典型 0.02~0.15。0.18 是留了余量的分界。
     */
    @Volatile
    var spectrumFlatGate: Float = 0.18f

    /**
     * 高频（≥3kHz）能量占比门槛。快门典型 0.3~0.6，人声典型 <0.15。
     */
    @Volatile
    var spectrumHighGate: Float = 0.28f

    /**
     * 低频（≤500Hz）能量占比上限。人声能量集中在这里。
     */
    @Volatile
    var spectrumLowGate: Float = 0.55f

    /**
     * 频谱质心下限（Hz）。快门声重心明显偏高。
     */
    @Volatile
    var centroidGate: Float = 1200f

    /** 最近一次的频谱摘要（供诊断输出）。 */
    @Volatile
    var lastSpectrumInfo: String = ""
        private set

    // ---------------- 频谱采样（校准用） ----------------

    /**
     * 是否处于"采样"状态。
     * 处于该状态时，每次出现**显著峰值**都会自动记录一条完整频谱快照。
     * 这样用户不需要精准掐时机——按一下快门，最强的那一块会被记下来。
     */
    @Volatile
    var samplingActive: Boolean = false

    private val samples = java.util.Collections.synchronizedList(ArrayList<SampleRecord>())

    data class SampleRecord(
        val atMillis: Long,
        val rms: Float,
        val snapshot: String,
        /** 降噪前 RMS，用于对比降噪效果。 */
        val rawRms: Float = 0f,
        /** 波形缩略（-1..1），用于绘制。 */
        val waveform: FloatArray = FloatArray(0),
    )

    /** 开始/结束采样。 */
    fun setSampling(on: Boolean) {
        samplingActive = on
        settleBlocks = 0
        stableCount = 0
        prevBlockRms = 0f
        if (on) {
            synchronized(samples) { samples.clear() }
            synchronized(timbreShapes) { timbreShapes.clear() }
            synchronized(timbreDurations) { timbreDurations.clear() }
            samplePhase = SamplePhase.SETTLING
            Logx.i(TAG, "采样开启：先自动稳定环境，之后等快门声")
        } else {
            samplePhase = SamplePhase.IDLE
            Logx.i(TAG, "采样结束，共 ${samples.size} 条，音色样本 ${timbreShapes.size} 条")
        }
    }

    /**
     * 用采样到的数据训练音色模板。
     *
     * 这才是校准的正解：不是找音量门槛，而是学"你的快门声长什么样"。
     */
    fun trainTimbre(): String {
        val shapes = synchronized(timbreShapes) { ArrayList(timbreShapes) }
        val durs = synchronized(timbreDurations) { ArrayList(timbreDurations) }
        if (shapes.isEmpty()) {
            return "还没有音色样本。请先采样（按几次快门）。"
        }
        val report = timbre.train(shapes, durs)
        Logx.i(TAG, report)
        return report
    }

    /** 清除训练结果。 */
    fun resetTimbre() {
        timbre.reset()
        synchronized(timbreShapes) { timbreShapes.clear() }
        synchronized(timbreDurations) { timbreDurations.clear() }
    }

    fun clearSamples() {
        synchronized(samples) { samples.clear() }
    }

    /** 导出采样结果为多行文本。 */
    fun exportSamples(): String {
        val copy = synchronized(samples) { ArrayList(samples) }
        if (copy.isEmpty()) return "(没有采到样本，请先开启采样并发出目标声音)"
        val sb = StringBuilder()
        sb.appendLine("=== 频谱采样 ${copy.size} 条 ===")
        copy.forEachIndexed { i, s ->
            sb.appendLine("[$i] $s")
        }
        return sb.toString()
    }

    fun sampleCount(): Int = synchronized(samples) { samples.size }

    /**
     * 最近采集到的频谱形状（24 带，已归一化到 0..1），供采样时实时预览。
     *
     * 为什么归一化到 0..1 而不是给原始能量：
     * UI 只需要画相对高低，原始能量跨度可能几个数量级，直接画看不出形状。
     */
    fun recentSpectra(n: Int = 3): List<FloatArray> {
        val shapes = synchronized(timbreShapes) { ArrayList(timbreShapes) }
        return shapes.takeLast(n).map { raw ->
            val max = raw.maxOrNull() ?: 0f
            if (max < 1e-9f) FloatArray(raw.size) else FloatArray(raw.size) { raw[it] / max }
        }
    }

    /** 当前实时频谱形状（不采样时也能看，用于确认麦克风收到的声音长什么样）。 */
    fun liveSpectrum(): FloatArray {
        val v = lastLiveSpectrum
        val max = v.maxOrNull() ?: 0f
        return if (max < 1e-9f) FloatArray(v.size) else FloatArray(v.size) { v[it] / max }
    }

    /** 最近 n 条样本的波形，供 UI 绘制。 */
    fun recentWaveforms(n: Int = 5): List<FloatArray> {
        val copy = synchronized(samples) { ArrayList(samples) }
        return copy.takeLast(n).map { it.waveform }.filter { it.isNotEmpty() }
    }

    /** 样本电平统计（用于一键校准）。 */
    data class SampleStats(
        val count: Int,
        val minRms: Float,
        val medianRms: Float,
        val maxRms: Float,
    )

    /**
     * 计算样本电平统计。
     *
     * 用**中位数**而不是均值：用户采样时可能不小心拍到环境噪声或说话，
     * 这类离群值会把均值拉偏，中位数对离群点稳健。
     */
    fun sampleStats(): SampleStats? {
        val copy = synchronized(samples) { ArrayList(samples) }
        if (copy.isEmpty()) return null
        val sorted = copy.map { it.rms }.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }
        return SampleStats(
            count = sorted.size,
            minRms = sorted.first(),
            medianRms = median,
            maxRms = sorted.last(),
        )
    }

    /** 当前噪声底（供 UI 判断麦克风收音质量）。 */
    @Volatile
    private var noiseFloorShared: Float = 0.004f

    /** 最近一块的 RMS/峰值，用于没有命中时也向 UI 上报电平。 */
    @Volatile
    private var lastBlockRms: Float = 0f

    @Volatile
    private var lastBlockPeak: Float = 0f

    /** 实际使用的录音源名称（UI 显示，便于判断系统是否加了处理）。 */
    @Volatile
    var activeSourceName: String = ""
        private set

    /** 快门音色模板（训练后用于识别，替代单纯的电平阈值）。 */
    val timbre = TimbreTemplate()

    /** 最近一块的频谱形状（24 带原始能量），供实时预览。 */
    @Volatile
    private var lastLiveSpectrum: FloatArray = FloatArray(TimbreTemplate.BANDS)

    /** 采样期间收集的 24 带音色向量，训练模板用。 */
    private val timbreShapes = java.util.Collections.synchronizedList(ArrayList<FloatArray>())


    /** 采样期间收集的持续时间（ms）。 */
    private val timbreDurations = java.util.Collections.synchronizedList(ArrayList<Float>())

    // ---------------- 降噪 ----------------

    /** 是否启用降噪。低端设备底噪高时强烈建议开启。 */
    @Volatile
    var denoiseEnabled: Boolean = true

    /** 降噪强度 0..1。越大噪声压得越低，但过大会引入"音乐噪声"。 */
    @Volatile
    var denoiseStrength: Float = 0.7f

    /**
     * 采样阶段：由程序自动推进，不需要用户数时间。
     *
     * - [SETTLING]：刚开启，正在让包络/降噪收敛，此时**不记录**任何样本
     * - [CAPTURING]：环境已稳定，等待用户按快门，采到样本才记录
     * - [DONE]：已采够目标数量，自动结束
     *
     * 之所以要分阶段：采样刚启动时包络还在收敛（见 TransientDetector 的预热期），
     * 这时任何声音都不可信。之前让用户"自己数 5 秒"就是因为没把这件事交给程序。
     */
    enum class SamplePhase { IDLE, SETTLING, CAPTURING, DONE }

    @Volatile
    var samplePhase: SamplePhase = SamplePhase.IDLE
        private set

    /** 当前稳定进度 0..1，供 UI 显示"还差多少"。 */
    fun settleProgress(): Float =
        (stableCount.toFloat() / SETTLE_BLOCKS_NEEDED).coerceIn(0f, 1f)

    /**
     * 采到这个数量就自动结束。
     *
     * 取值范围见 [SampleRange]：6~20 条，默认 8。
     * 实测 6 条以上模板已经稳定，再多对相似度判别几乎没有帮助，
     * 只是让用户多按几次快门。上限留到 12 是为了应对环境特别吵的场合。
     */
    @Volatile
    var sampleTarget: Int = SampleRange.DEFAULT
        set(value) {
            field = value.coerceIn(SampleRange.MIN, SampleRange.MAX)
        }

    /** 稳定阶段的块计数（用于自动结束 SETTLING）。 */
    private var settleBlocks = 0

    /** 连续满足"稳定"的块数。 */
    private var stableCount = 0

    /** 上一块的 RMS（判断变化率用）。 */
    @Volatile
    private var prevBlockRms: Float = 0f

    /** 稳定判定所需块数：连续 25 块（约 0.5 秒）变化率足够小。 */
    private val SETTLE_BLOCKS_NEEDED = 25

    /** 稳定判定的硬上限：150 块（3 秒）后无论如何都开始采集。 */
    private val SETTLE_MAX_BLOCKS = 150

    /** 自动结束采样（采够数量时调用）。 */
    fun finishSampling() {
        samplingActive = false
        samplePhase = SamplePhase.DONE
        Logx.i(TAG, "采样自动结束，共 ${samples.size} 条")
    }

    /**
     * 推进采样阶段。每块调用一次。
     *
     * 稳定判据不能只看"当前电平是否高于噪声底"，因为两者同源：
     * 噪声底（slow）本身就在跟随电平，这个比较几乎永远成立，
     * 反而在降噪把 slow 压得极低时会失效。
     *
     * 改用**变化率**判断：相邻两块的 RMS 差异足够小 = 环境稳定。
     * 这直接对应"有没有人在说话/走来走去"这类干扰。
     *
     * @return true 表示本次从 SETTLING 刚进入 CAPTURING（供 UI 提示"可以按快门了"）
     */
    private fun advanceSamplePhase(): Boolean {
        if (samplePhase != SamplePhase.SETTLING) return false

        val cur = lastBlockRms
        val prev = prevBlockRms
        prevBlockRms = cur

        // 相对变化率：|cur - prev| / max(cur, prev)
        val diff = kotlin.math.abs(cur - prev)
        val ref = kotlin.math.max(cur, prev).coerceAtLeast(1e-6f)
        val relChange = diff / ref

        // 绝对变化也要小，避免极安静时相对值虚高
        val quiet = cur < 0.05f
        val stableNow = relChange < 0.25f && quiet

        // 兜底：无论环境如何，最多等 3 秒（150 块）就强制开始
        settleBlocks++
        if (settleBlocks >= SETTLE_MAX_BLOCKS) {
            samplePhase = SamplePhase.CAPTURING
            Logx.w(TAG, "稳定判定超时，强制进入采集中（当前环境可能偏吵）")
            return true
        }

        if (stableNow) {
            stableCount++
            if (stableCount >= SETTLE_BLOCKS_NEEDED) {
                samplePhase = SamplePhase.CAPTURING
                Logx.i(TAG, "环境已稳定（relChange=%.2f），开始等待快门声".format(relChange))
                return true
            }
        } else {
            stableCount = (stableCount - 3).coerceAtLeast(0)
        }
        return false
    }

    /** 降噪后噪声功率估计（供 UI 显示降噪效果）。 */
    fun denoiseNoisePower(): Float = lastDenoiser?.estimatedNoisePower ?: 0f

    /** 降噪带来的信噪比提升（dB，负值表示能量被压低）。 */
    fun denoiseSuppressionDb(): Float = lastDenoiser?.lastSuppressionDb ?: 0f

    private var lastDenoiser: NoiseSuppressor? = null

    fun currentNoiseFloor(): Float = noiseFloorShared

    /** 记录一条样本（只在 sampling 开启且达到显著峰值时调用）。 */
    private fun recordSample(rms: Float, snapshot: String, rawRms: Float = 0f, pcm: ShortArray? = null, n: Int = 0) {
        synchronized(samples) {
            // 上限按"目标数 + 余量"算，避免用户重复点击导致过早停采
            if (samples.size >= 40) return
            // 波形缩略：把 PCM 压到 64 点（取每段的峰值，保留瞬态形状）
            //
            // ⚠️ 这里的边界必须夹紧到 **数组实际长度**，不能只用 n。
            // 曾经因为 n 与 pcm.size 不一致（重连后缓冲区尺寸变化）
            // 导到 pcm[882] 越界，表现为"录一次之后再录就麦克风出错"。
            val wave = if (pcm != null && n > 0) {
                val limit = kotlin.math.min(n, pcm.size)
                if (limit <= 0) {
                    FloatArray(0)
                } else {
                    val points = 64
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
                    out
                }
            } else {
                FloatArray(0)
            }
            samples.add(
                SampleRecord(
                    atMillis = System.currentTimeMillis(),
                    rms = rms,
                    snapshot = snapshot,
                    rawRms = rawRms,
                    waveform = wave,
                )
            )
        }
    }

    /**
     * 一次性应用一组调参（供 UI 预设 / 一键校准使用）。
     *
     * 新版判据由 [TransientDetector] 的 riseGate 与 absoluteFloor 决定，
     * 二者都从 sensitivity 换算。所以这里只需要设定 sensitivity，
     * [floorOverride] 非空时额外覆盖绝对下限（校准用）。
     *
     * @param sens 0..1 灵敏度
     * @param floor 绝对门限；<=0 表示不覆盖
     * @param lowRatio 低频占比上限（兼容字段，新版由频谱门限决定）
     */
    fun applyTuning(sens: Float, floor: Float, lowRatio: Float) {
        sensitivity = sens.coerceIn(0f, 1f)
        if (floor > 0f) {
            absoluteFloor = floor.coerceIn(0.0001f, 0.05f)
        }
        maxLowFreqRatio = lowRatio.coerceIn(0.3f, 1f)
        val probe = TransientDetector()
        probe.applySensitivity(sensitivity)
        Logx.i(
            TAG,
            "调参: sens=%.2f riseGate=%.1f absFloor=%.5f lowRatio=%.2f"
                .format(sensitivity, probe.riseGate, probe.absoluteFloor, maxLowFreqRatio)
        )
    }

    /**
     * 频谱判据调参。
     * @param flat 平坦度门槛（越低越容易通过）
     * @param high 高频占比门槛
     * @param low 低频占比上限
     * @param centroid 质心下限 Hz
     */
    fun applySpectrumTuning(flat: Float, high: Float, low: Float, centroid: Float) {
        spectrumFlatGate = flat.coerceIn(0.02f, 0.8f)
        spectrumHighGate = high.coerceIn(0.02f, 0.8f)
        spectrumLowGate = low.coerceIn(0.2f, 1f)
        centroidGate = centroid.coerceIn(200f, 4000f)
        Logx.i(TAG, "频谱门限: flat=%.2f high=%.2f low=%.2f cent=%.0f"
            .format(spectrumFlatGate, spectrumHighGate, spectrumLowGate, centroidGate))
    }

    /**
     * 当前实际生效的触发阈值（供 UI / 校准结果显示）。
     *
     * 与 [TransientDetector] 保持同一公式：
     *   阈值 = max(absoluteFloor, 噪声底 × riseGate)
     * 其中 riseGate 与 absoluteFloor 都由 sensitivity 换算而来。
     */
    fun describeThreshold(): Float {
        val probe = TransientDetector()
        probe.applySensitivity(sensitivity)
        return maxOf(probe.absoluteFloor, noiseFloorShared * probe.riseGate)
    }

    /** 当前生效的上升沿倍数（用于 UI 显示判据）。 */
    fun currentRiseGate(): Float {
        val probe = TransientDetector()
        probe.applySensitivity(sensitivity)
        return probe.riseGate
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** 录音线程是否正在运行（供上层判断采样是否可能有效）。 */
    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    fun start(listener: Listener): Boolean {
        if (!hasPermission()) {
            listener.onError(SecurityException("缺少 RECORD_AUDIO 权限"))
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            running.set(false)
            listener.onError(IllegalStateException("设备不支持 44.1kHz 单声道录音"))
            return false
        }
        val bufSize = minBuf.coerceAtLeast(BLOCK * 4)

        val rec = try {
            createRecord(bufSize)
        } catch (t: Throwable) {
            running.set(false)
            listener.onError(t)
            return false
        }
        record = rec
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            running.set(false)
            listener.onError(t)
            safeRelease(rec)
            return false
        }

        val t = Thread({ loop(rec, listener) }, "audio-trigger")
        t.priority = Thread.MAX_PRIORITY
        t.isDaemon = true
        thread = t
        t.start()
        Logx.i(TAG, "音频触发已启动 (sensitivity=$sensitivity floor=$absoluteFloor)")
        return true
    }

    private fun createRecord(bufSize: Int): AudioRecord {
        // 优先 UNPROCESSED（绕开系统 AGC/降噪，瞬态最干净）。
        // 但很多机型不开放 UNPROCESSED（返回未初始化），必须逐级回退：
        //   UNPROCESSED → VOICE_RECOGNITION（通常无 AGC）→ CAMCORDER → MIC
        // 实测小米部分机型只有 VOICE_RECOGNITION 能拿到干净信号。
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
        )
        val names = arrayOf("UNPROCESSED", "VOICE_RECOGNITION", "CAMCORDER", "MIC")
        var last: Throwable? = null
        for ((idx, src) in sources.withIndex()) {
            try {
                val r = AudioRecord(src, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Logx.i(TAG, "AudioRecord source=${names[idx]}($src) buf=$bufSize")
                    activeSourceName = names[idx]
                    return r
                }
                r.release()
                last = IllegalStateException("AudioRecord 初始化失败 source=${names[idx]}")
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("无法创建 AudioRecord")
    }

    private fun loop(recIn: AudioRecord, listener: Listener) {
        var rec = recIn
        val buf = ShortArray(BLOCK)
        // 频谱分析器：状态常驻，跨块连续（滤波器延迟不会在块边界断开）
        val spectrum = SpectrumFeatures(SAMPLE_RATE)
        // 降噪：谱减法，压制低端设备的高底噪，同时保留瞬态
        val denoiser = NoiseSuppressor(BLOCK)
        denoiser.enabled = denoiseEnabled
        denoiser.setStrength(denoiseStrength)
        lastDenoiser = denoiser
        // 原始 PCM 副本（降噪前的电平，用于对比显示）
        val rawBuf = ShortArray(BLOCK)
        // 自适应瞬态检测器
        val detector = TransientDetector(SAMPLE_RATE)
        val prevSample = floatArrayOf(0f)
        var lastLevelReport = 0L
        var lastSampleAtNanos = 0L

        // 初始化检测器参数
        detector.applySensitivity(sensitivity)
        var appliedSens = sensitivity

        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        // 读错误通常意味着录音被系统抢占（来电/其他 App 录音）。
                        // 不直接退出，尝试重建 AudioRecord 后继续——
                        // 现场拍摄时服务被杀会直接导致补光失效，必须自愈。
                        Logx.w(TAG, "AudioRecord.read 错误 $n，尝试重建录音器")
                        val rebuilt = tryRebuildRecord()
                        if (rebuilt == null) {
                            listener.onError(IllegalStateException("AudioRecord 读取失败，且重建失败"))
                            break
                        }
                        rec = rebuilt
                    }
                    continue
                }
                val nowNanos = System.nanoTime()

                // 灵敏度变了就重新应用（允许运行中实时调整）
                if (sensitivity != appliedSens) {
                    appliedSens = sensitivity
                    detector.applySensitivity(appliedSens)
                }

                // 降噪参数实时同步
                denoiser.enabled = denoiseEnabled
                denoiser.setStrength(denoiseStrength)

                // 保留降噪前的副本，用于计算"原始电平"给 UI 对比
                System.arraycopy(buf, 0, rawBuf, 0, n)
                // 谱减法降噪：压制稳态噪声，瞬态基本不受影响
                if (denoiseEnabled) {
                    denoiser.process(buf, n)
                }

                // 频谱门限由灵敏度推导
                val gates = TransientDetector.spectrumGatesFor(sensitivity)
                val hit = detector.process(
                    buf = buf,
                    n = n,
                    prevSample = prevSample,
                    nowNanos = nowNanos,
                    spectrum = spectrum,
                    spectrumEnabled = SPECTRUM_ENABLED,
                    spectrumGates = gates,
                    timbre = timbre,
                )

                val thresholdNow = maxOf(
                    detector.absoluteFloor,
                    detector.noiseFloor * detector.riseGate,
                )
                noiseFloorShared = detector.noiseFloor
                lastSpectrumInfo = "flat=%.2f 高=%.2f 低=%.2f".format(
                    hit?.flatness ?: 0f, hit?.highRatio ?: 0f, hit?.lowRatio ?: 0f
                )

                if (hit != null) {
                    val blockNanos = (n.toLong() * 1_000_000_000L) / SAMPLE_RATE
                    val onset = nowNanos - blockNanos
                    Logx.d(
                        TAG,
                        "✓触发 rms=%.4f fast/slow=%.1f flat=%.2f 高=%.2f 低=%.2f cent=%.0fHz"
                            .format(hit.rms, hit.riseRatio, hit.flatness, hit.highRatio,
                                hit.lowRatio, hit.centroidHz)
                    )
                    listener.onTransient(hit.rms, onset)
                } else if (detector.lastRejectReason.isNotBlank()) {
                    Logx.d(TAG, "×拦截 ${detector.lastRejectReason}")
                }

                // 采样模式：记录显著块，供一键校准。
                // 注意用 **当前块 RMS** 判断而不是只看 hit——
                // 采样阶段的意义是"把声音都记下来"，包括被判据拦截的，
                // 否则用户永远采不到"判据认为不对"的样本，无法据此调参。
                //
                // 门槛要足够低：采样是"宁滥勿缺"。如果门槛太高，
                // 用户在安静环境里按快门也采不到，会以为功能坏了。
                // 采样阶段自动推进：程序自己判断环境何时稳定，不让用户数时间。
                if (samplingActive) {
                    lastBlockRms = detector.lastRms
                    if (advanceSamplePhase()) {
                        listener.onSamplePhaseChanged(true)
                    }
                }

                val blockRmsNow = detector.lastRms
                // 采样门槛用**原始信号**（降噪前）判定，并给一个明确的绝对下限。
                //
                // 之前用 noiseFloor×1.5：降噪后 noiseFloor 被压得很低，
                // 门槛几乎贴地，结果任何风吹草动都算一条样本，而且会导致
                // 「明明没按快门却一直采到东西」的观感。
                val sampleGate = maxOf(detector.noiseFloor * 2.0f, 0.0025f)
                // 实时频谱：无论是否采样都更新一次，采样时用户能直接看到声音形状
                if (samplingActive) {
                    val live = spectrum.timberVector(rawBuf, n)
                    if (live.any { it > 1e-9f }) lastLiveSpectrum = live
                }
                // 只有进入 CAPTURING 阶段才记录：SETTLING 期间包络还在收敛，采到的都不可信
                if (samplingActive && samplePhase == SamplePhase.CAPTURING &&
                    blockRmsNow > sampleGate
                ) {
                    val sinceSampleMs = (nowNanos - lastSampleAtNanos) / 1_000_000L
                    if (sinceSampleMs >= 150L) {
                        lastSampleAtNanos = nowNanos
                        // 原始 RMS 必须用 **降噪前** 的 rawBuf 算，
                        // 否则 raw 和 rms 是同一份数据，永远相等，看不出降噪效果。
                        var rawSum = 0.0
                        for (i in 0 until n) {
                            val s = rawBuf[i] / 32768f
                            rawSum += s.toDouble() * s
                        }
                        val rawRms = sqrt(rawSum / n).toFloat()
                        val ratioToNoise =
                            if (detector.noiseFloor > 1e-9f) blockRmsNow / detector.noiseFloor else 0f
                        val riseStr = if (hit != null) "%.1f".format(hit.riseRatio) else "-"
                        val snap = buildString {
                            append("rms=%.4f raw=%.4f rise=%s xNoise=%.1f"
                                .format(blockRmsNow, rawRms, riseStr, ratioToNoise))
                            append(" flat=%.2f 高=%.2f 低=%.2f"
                                .format(detector.lastFlatness, detector.lastHighRatio, detector.lastLowRatio))
                            if (hit == null) append(" [未过判据]")
                        }
                        try {
                            recordSample(blockRmsNow, snap, rawRms, buf, n)
                            // 收集音色向量（24 带），训练模板用。
                            // 用降噪前的原始信号，避免降噪的谱减把形状改掉。
                            val shape = spectrum.timberVector(rawBuf, n)
                            if (shape.any { it > 1e-9f }) {
                                synchronized(timbreShapes) {
                                    if (timbreShapes.size < 60) timbreShapes.add(shape)
                                }
                                // 记录该次事件的持续长度（块数 × 20ms）
                                synchronized(timbreDurations) {
                                    if (timbreDurations.size < 60) {
                                        timbreDurations.add((n.toFloat() / SAMPLE_RATE) * 1000f)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            // 采样记录失败绝不能拖垮整条音频链路——
                            // 它会连带让"麦克风出错"，把补光一起废掉。
                            Logx.e(TAG, "记录样本失败（已忽略）: ${t.message}", t)
                        }
                        // 采够目标数量就自动停，用户不用再点一次
                        if (sampleCount() >= sampleTarget) {
                            finishSampling()
                            listener.onSamplePhaseChanged(false)
                        }
                    }
                }

                // 实时上报
                if (nowNanos - lastLevelReport > 50_000_000L) {
                    lastLevelReport = nowNanos
                    lastBlockRms = detector.lastRms
                    lastBlockPeak = detector.lastPeak
                    listener.onLevel(
                        detector.lastRms,
                        thresholdNow,
                        detector.lastPeak,
                        "flat=%.2f 高=%.2f 低=%.2f".format(
                            detector.lastFlatness, detector.lastHighRatio, detector.lastLowRatio
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            if (running.get()) listener.onError(t)
        } finally {
            running.set(false)
        }
    }

    /**
     * 重建 AudioRecord（录音被系统抢占后自愈）。
     *
     * 现场拍摄时如果录音被电话/其他 App 打断，服务不能直接死掉，
     * 否则用户会发现"补光突然不响应了"。这里重试 3 次，每次间隔 200ms。
     *
     * @return 新的可用 AudioRecord；失败返回 null
     */
    private fun tryRebuildRecord(): AudioRecord? {
        safeRelease(record)
        record = null
        for (attempt in 1..3) {
            if (!running.get()) return null
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                return null
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuf <= 0) continue
            val bufSize = minBuf.coerceAtLeast(BLOCK * 4)
            try {
                val r = createRecord(bufSize)
                r.startRecording()
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record = r
                    Logx.i(TAG, "录音器重建成功（第 $attempt 次尝试）")
                    return r
                }
                safeRelease(r)
            } catch (t: Throwable) {
                Logx.w(TAG, "重建第 $attempt 次失败: ${t.message}")
            }
        }
        Logx.e(TAG, "录音器重建失败，请检查是否有其他 App 正在录音")
        return null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            safeRelease(record)
            record = null
            return
        }
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        safeRelease(record)
        record = null
        thread = null
        Logx.i(TAG, "音频触发已停止")
    }
    private fun safeRelease(r: AudioRecord?) {
        try {
            r?.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "AudioTrigger"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20ms 一块，兼顾延迟与 CPU */
        private const val BLOCK = SAMPLE_RATE / 50
        private const val REFRACTORY_MS = 120L

        /** 是否启用频谱形状确认（排除人声/音乐）。关掉则只看上升沿。 */
        @Volatile
        var SPECTRUM_ENABLED = true
    }
}
