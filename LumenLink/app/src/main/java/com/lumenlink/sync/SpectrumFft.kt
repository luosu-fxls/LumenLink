package com.lumenlink.sync

import kotlin.math.sqrt

/**
 * 轻量 FFT 分析器（纯频谱，不做降噪）。
 *
 * 与 [NoiseSuppressor] 里的 FFT 是同一套基 2 实现，
 * 但这里只关心"当前这块的频率成分"，用于提取音色指纹。
 *
 * ⚠️ FFT 长度必须是 2 的幂 —— 882（20ms@44.1kHz）不是，必须上取到 1024。
 * 这个坑已经在 NoiseSuppressor 上踩过一次，这里从构造就保证。
 */
class SpectrumFft(blockSize: Int = 1024) {

    /** 自动上取到 2 的幂。 */
    private val fftSize: Int = run {
        var s = 1
        while (s < blockSize) s = s shl 1
        s
    }

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val win = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
    }
    private val mag = FloatArray(fftSize / 2 + 1)
    private val sampleRate = 44_100f

    /** 处理一块 PCM，内部保存幅度谱。 */
    fun process(pcm: ShortArray, length: Int) {
        val n = minOf(length, pcm.size, fftSize)
        for (i in 0 until n) {
            re[i] = (pcm[i] / 32768f) * win[i]
            im[i] = 0f
        }
        for (i in n until fftSize) {
            re[i] = 0f
            im[i] = 0f
        }
        transform(re, im, false)
        val half = fftSize / 2 + 1
        for (k in 0 until half) {
            mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        }
    }

    fun magnitudes(): FloatArray = mag

    /** 每个 bin 对应的频率间隔（Hz）。 */
    fun binHz(): Float = sampleRate / fftSize

    private fun transform(a: FloatArray, b: FloatArray, inverse: Boolean) {
        val n = a.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = a[i]; a[i] = a[j]; a[j] = t
                t = b[i]; b[i] = b[j]; b[j] = t
            }
        }
        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = sign * 2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = a[i + k]
                    val uIm = b[i + k]
                    val vRe = a[i + k + len / 2] * curRe - b[i + k + len / 2] * curIm
                    val vIm = a[i + k + len / 2] * curIm + b[i + k + len / 2] * curRe
                    a[i + k] = uRe + vRe
                    b[i + k] = uIm + vIm
                    a[i + k + len / 2] = uRe - vRe
                    b[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                a[i] *= scale
                b[i] *= scale
            }
        }
    }
}
