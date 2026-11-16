package com.lumenlink.sync

/**
 * 音色采样条数的取值范围。
 *
 * 放在 sync 包里是因为**两个层都要用**：
 *  - [AudioTrigger.sampleTarget] 在这里做 coerceIn，防止越界；
 *  - 设置页的 Slider 用同一组常量渲染刻度和提示文案。
 *
 * 之前 UI 里写死 5..20、AudioTrigger 里又写了一遍 5..20，
 * 两处一旦不同步就会出现"滑到 15 但实际被夹到 10"这类鬼问题。
 *
 * 为什么建议区间是 6~10、上限却放到 20：
 *  24 带音色模板对每个频带取**中位数**，6 条样本已足以排除单条异常
 *  （比如某次快门刚好混进一声咳嗽），再多对相似度判别帮助递减。
 *  但某些场景（环境噪声大、快门声本身不稳定）多采几条更稳，
 *  所以上限放宽到 20，让用户自己权衡"多按几次快门"的成本。
 */
object SampleRange {
    /** 滑块下限。 */
    const val MIN = 6

    /** 滑块上限。 */
    const val MAX = 20

    /** 默认值：落在建议区间内，无需调整即可直接用。 */
    const val DEFAULT = 8

    /** 建议区间下限（只用于提示文案）。 */
    const val RECOMMEND_MIN = 6

    /** 建议区间上限（只用于提示文案）。 */
    const val RECOMMEND_MAX = 10
}
