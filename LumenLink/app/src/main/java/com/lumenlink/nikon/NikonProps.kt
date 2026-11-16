package com.lumenlink.nikon

import com.lumenlink.ptp.DevicePropDesc
import com.lumenlink.ptp.Ptp

/**
 * 属性「语义分类」。
 *
 * 这是本工程最重要的一个设计决定：**不硬编码尼康私有属性码**。
 * 原因很实际——尼康的私有属性码（0xD0xx / 0xD1xx）在不同机身/固件上并不完全一致，
 * 从各种开源项目里抄一张表然后赌它对你的机器成立，是必翻车的做法。
 *
 * 改为：让机身把 DevicePropDesc 交出来（含枚举值），我们**按值的形态**判断这是哪个参数。
 *  "1/250"、"1.3\""、"0.5\"" 一定是快门；"100/200/400/800…" 一定是 ISO；
 *  "f/2.8" 一定是光圈。这样任何尼康机身（甚至佳能/索尼走 PTP 时）都能自动对上。
 *
 * 只有一种情况需要人工兜底：某机身把快门速度编码成纯数字（见 [exposureUnitMicros]），
 * 这时靠 0x500D 标准属性 + 单位自校准（[resolveExposureRaw]）来处理。
 */
object NikonProps {

    enum class Kind { SHUTTER, ISO, FNUMBER, EXPOSURE_COMP, WHITE_BALANCE, FLASH, UNKNOWN }

    /**
     * 额外要扫的属性码。
     *
     * [NikonOps.DPC_ShutterSpeed] = 0xD100 是少数被多个开源项目确认过的尼康私有属性，
     * 它的值是**文本**（"1/250"），所以上层的语义识别能直接认得。
     * 其余尼康私有码在不同代机身上不一致，不做猜测——靠 GetVendorPropCodes(0x90CA) 现问现拿。
     */
    val EXTRA_CANDIDATE_CODES: IntArray = intArrayOf(
        NikonOps.DPC_ShutterSpeed,
    )

    private val FRACTION = Regex("^\\d+\\s*/\\s*\\d+$")
    private val SECONDS_TICK = Regex("^\\d+(\\.\\d+)?\\s*[\"″s]$")
    private val PLAIN_NUMBER = Regex("^\\d{1,6}$")

    /**
     * 按枚举值形态判定属性语义。
     * 判定需要「至少 60% 的值符合」才算，避免误伤。
     */
    fun classify(desc: DevicePropDesc): Kind {
        val vals = desc.enumValues.filter { it.isNotBlank() }
        if (vals.isEmpty()) {
            // Range 型属性没有枚举值，只能靠标准属性码判断
            return when (desc.propertyCode) {
                Ptp.DPC_ExposureTime -> Kind.SHUTTER
                Ptp.DPC_ExposureIndex -> Kind.ISO
                Ptp.DPC_FNumber -> Kind.FNUMBER
                Ptp.DPC_ExposureBiasCompensation -> Kind.EXPOSURE_COMP
                Ptp.DPC_WhiteBalance -> Kind.WHITE_BALANCE
                Ptp.DPC_FlashMode -> Kind.FLASH
                else -> Kind.UNKNOWN
            }
        }

        val shutterHits = vals.count { looksLikeShutter(it) }
        if (shutterHits >= vals.size * 0.6 && vals.size >= 3) return Kind.SHUTTER

        val isoHits = vals.count { looksLikeIso(it) }
        if (isoHits >= vals.size * 0.6 && vals.size >= 3) return Kind.ISO

        val fHits = vals.count { looksLikeFNumber(it) }
        if (fHits >= vals.size * 0.6 && vals.size >= 3) return Kind.FNUMBER

        val compHits = vals.count { looksLikeExposureComp(it) }
        if (compHits >= vals.size * 0.6 && vals.size >= 3) return Kind.EXPOSURE_COMP

        return when (desc.propertyCode) {
            Ptp.DPC_ExposureTime -> Kind.SHUTTER
            Ptp.DPC_ExposureIndex -> Kind.ISO
            Ptp.DPC_FNumber -> Kind.FNUMBER
            else -> Kind.UNKNOWN
        }
    }

    fun looksLikeShutter(v: String): Boolean {
        val s = v.trim()
        return FRACTION.matches(s) || SECONDS_TICK.matches(s) ||
            s == "Bulb" || s == "BULB" || s == "Time" || s == "X250"
    }

    fun looksLikeIso(v: String): Boolean {
        val s = v.trim().uppercase().removePrefix("ISO").trim()
        if (s.isEmpty()) return false
        if (s.startsWith("HI") || s.startsWith("LO")) return true
        val n = s.toIntOrNull() ?: return false
        return n in 32..409_600
    }

    fun looksLikeFNumber(v: String): Boolean {
        val s = v.trim().lowercase().removePrefix("f/").removePrefix("f")
        val d = s.toDoubleOrNull() ?: return false
        return d in 0.7..64.0
    }

    fun looksLikeExposureComp(v: String): Boolean {
        val s = v.trim()
        if (!(s.startsWith("+") || s.startsWith("-"))) return false
        val d = s.removePrefix("+").toDoubleOrNull() ?: return false
        return d in -6.0..6.0
    }

    // ---------------- 数值型曝光时间的单位自校准 ----------------

    /**
     * PTP 的 ExposureTime(0x500D) 在多数实现里是「枚举 + 整数」，但**单位各家不同**
     * （10µs / 100µs / 1µs 都见过）。硬编码单位必然出错。
     *
     * 这里的做法：候选单位依次试，取「算出来的原始值恰好出现在机身返回的枚举表里」的那个。
     * 这样单位是机身自己告诉我们的，不需要猜。
     */
    private val UNIT_CANDIDATES_US = intArrayOf(10, 100, 1)

    /** 已校准的单位（微秒），0 表示尚未校准。 */
    @Volatile
    var exposureUnitMicros: Int = 0
        private set

    /** 由 [NikonCamera] 在校准时写入。 */
    fun setExposureUnit(micros: Int) {
        if (micros > 0) exposureUnitMicros = micros
    }

    /**
     * "1/250" -> 机身的原始枚举值。
     * @return null 表示该值不在机身允许集合内（此时应该拒绝下发，而不是硬写）
     */
    fun resolveExposureRaw(desc: DevicePropDesc, shutterText: String): Long? {
        val millis = NikonMath.shutterTextToMillis(shutterText)
        if (millis <= 0) return null
        val micros = millis.toLong() * 1000L

        // 已是枚举型：用枚举表校验单位
        if (desc.enumValues.isNotEmpty()) {
            val numeric = desc.enumValues.mapNotNull { it.trim().toLongOrNull() }
            if (numeric.isNotEmpty()) {
                for (unit in UNIT_CANDIDATES_US) {
                    val raw = micros / unit
                    if (numeric.contains(raw)) {
                        exposureUnitMicros = unit
                        return raw
                    }
                }
                // 枚举里没有精确匹配，取最接近的（机身的枚举档位通常就是标准档，可以接受）
                val best = UNIT_CANDIDATES_US
                    .map { micros / it }
                    .flatMap { cand -> numeric.map { it to kotlin.math.abs(it - cand) } }
                    .minByOrNull { it.second }
                if (best != null && best.second <= 2) {
                    return best.first
                }
                return null
            }
        }

        // Range 型：直接用已校准单位，未校准则默认 10µs
        val unit = if (exposureUnitMicros > 0) exposureUnitMicros else 10
        return micros / unit
    }

    /** 反向：机身原始值 -> 毫秒，用于读回当前快门速度。 */
    fun exposureRawToMillis(raw: Long): Int {
        // Z6II 实测枚举表里含两个特殊值，必须先识别，否则会算出 42949 秒：
        //   0xFFFFFFFF (4294967295) = Bulb
        //   0xFFFFFFFD (4294967293) = Time
        if (raw == 0xFFFF_FFFFL || raw == 0xFFFF_FFFDL) return 0
        val unit = if (exposureUnitMicros > 0) exposureUnitMicros else 10
        return ((raw * unit) / 1000L).toInt().coerceAtLeast(1)
    }

    /** 机身原始值 -> 展示文本（正确处理 Bulb / Time）。 */
    fun exposureRawToText(raw: Long): String = when (raw) {
        0xFFFF_FFFFL -> "Bulb"
        0xFFFF_FFFDL -> "Time"
        else -> NikonMath.millisToShutterText(exposureRawToMillis(raw))
    }

    fun isSpecialExposure(raw: Long): Boolean =
        raw == 0xFFFF_FFFFL || raw == 0xFFFF_FFFDL

    // ---------------- 标准属性的可读格式化 ----------------

    /**
     * 标准 PTP 曝光属性的编码（已由 D7100 实测 dump 证实）：
     *  - 0x5007 FNumber   UINT16，值 = 光圈 × 100（800 = f/8）
     *  - 0x500F ISO       UINT16，就是 ISO 值（1600 = ISO 1600）
     *  - 0x5010 曝光补偿   INT16，1/1000 EV（-5000..+5000）
     *  - 0x500D 快门      UINT32，单位未在标准中固定，见上面的自校准
     */
    fun formatStandardValue(code: Int, raw: String): String {
        val v = raw.trim().toLongOrNull() ?: return raw.trim()
        return when (code) {
            Ptp.DPC_FNumber -> "f/%.1f".format(v / 100.0)
            Ptp.DPC_ExposureIndex -> "ISO $v"
            Ptp.DPC_ExposureBiasCompensation -> {
                val ev = v / 1000.0
                (if (ev > 0) "+" else "") + "%.1fEV".format(ev)
            }
            Ptp.DPC_ExposureTime -> NikonMath.millisToShutterText(exposureRawToMillis(v))
            else -> raw.trim()
        }
    }
}
