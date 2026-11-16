package com.lumenlink.sync

/**
 * 补光模式。
 *
 * ⚠️ 物理前提（这点必须写进产品逻辑，否则用户期望必然落空）：
 * 手机手电筒是**连续光源**（LED），不是氙气闪光管。它没有微秒级的闪光持续时间，
 * 也没有上万流明的瞬时功率。因此「当闪光灯用」在本工程里的正确定义是：
 *
 *  - CONTINUOUS：补光光源常亮，覆盖整个拍摄过程（适合人像、视频、静物长曝光）；
 *  - PULSE     ：只在「快门打开窗口」内点亮，按快门速度实时计算点亮时长，
 *                既省电又避免两次拍摄之间的光污染，还能显著降低过热风险；
 *  - BULB      ：长时间常亮（给 B 门/长曝光用），带占空比保护。
 *
 * 它无法与 1/1000s 的机械快门「同步闪光」，但能可靠地把曝光窗口照亮 —— 这才是
 * 手机 LED 对单反的真正价值：便宜的持续补光，而不是高速闪光。
 */
enum class LightMode {
    CONTINUOUS,
    PULSE,
}

/** 触发源，用于统计与 UI 展示。 */
enum class TriggerSource {
    CAMERA_PTP_EVENT,
    AUDIO,
    MANUAL,
    NONE,
}

/** 一次点亮的记录，给 UI 展示"同步质量"。 */
data class TriggerRecord(
    val source: TriggerSource,
    val atMillis: Long,
    val holdMs: Int,
    val offsetMs: Int,
)

data class SyncConfig(
    /** 当前相机快门速度（秒的倒数形式的毫秒值，例如 1/250s = 4ms） */
    val shutterMillis: Int = 4,
    /** 曝光结束后继续点亮的时长，用来吃掉机械/电子快门的抖动误差 */
    val tailMillis: Int = 8,
    /** 触发延迟补偿：音频触发时才需要（正数=提前点亮） */
    val compensationMillis: Int = 0,
    /** PULSE 模式下的最短/最长点亮时长，防止极端参数把 LED 烧了 */
    val minHoldMillis: Int = 20,
    val maxHoldMillis: Int = 2000,
    /** 触发后的不应期，避免连拍抖动造成疯狂开关 */
    val refractoryMillis: Int = 120,
) {
    /** 由快门速度推导出「需要点亮多久」。 */
    fun holdMillis(): Int =
        (shutterMillis + tailMillis).coerceIn(minHoldMillis, maxHoldMillis)
}
