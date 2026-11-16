package com.lumenlink.nikon

/**
 * PTP 事件码（15740 §12 标准事件）+ 尼康私有事件的判定策略。
 *
 * ⚠️ 对同步精度的重要认知：
 * 标准事件里跟「拍了一张」有关的是 [EV_CaptureComplete] 和 [EV_ObjectAdded]，
 * 但它们都是**事后**通知（曝光完成 / 文件已写入），延迟从几十毫秒到几百毫秒不等，
 * PTP 不是实时同步总线。所以：
 *
 *  - 它们适合做「确认这一帧拍到了」的统计与校验；
 *  - 真正的实时点亮只能靠常亮，或靠音频触发的瞬态检测（延迟 10~40ms）。
 *
 * 这个模块刻意不做「猜私有不存在的实时事件」这种事，而是提供学习机制：
 * 真机上按快门时打到的所有未知事件码都会被记录到 [observed]，可以在诊断里看到，
 * 若某机型的私有事件确实能实时反映快门，把它加进 [extraShutterCodes] 即可启用。
 */
object NikonEvents {

    // 标准事件（15740 §12.2）
    const val EV_Undefined = 0x4000
    const val EV_CancelTransaction = 0x4001
    const val EV_ObjectAdded = 0x4002
    const val EV_ObjectRemoved = 0x4003
    const val EV_StoreAdded = 0x4004
    const val EV_StoreRemoved = 0x4005
    const val EV_DevicePropChanged = 0x4006
    const val EV_ObjectInfoChanged = 0x4007
    const val EV_DeviceInfoChanged = 0x4008
    const val EV_RequestObjectTransfer = 0x4009
    const val EV_StoreFull = 0x400A
    const val EV_DeviceReset = 0x400B
    const val EV_StorageInfoChanged = 0x400C
    const val EV_CaptureComplete = 0x400D
    const val EV_CaptureOverflow = 0x400F
    const val EV_UnreportedStatus = 0x400E

    /** 额外被视为「快门触发」的事件码（用户/实验确定后写入）。 */
    val extraShutterCodes: MutableSet<Int> = linkedSetOf()

    /** 本次会话观察到的所有事件码 -> 出现次数，用于真机学习。 */
    private val observed = LinkedHashMap<Int, Int>()

    @Synchronized
    fun observe(code: Int) {
        observed[code] = (observed[code] ?: 0) + 1
    }

    @Synchronized
    fun observedSnapshot(): Map<Int, Int> = LinkedHashMap(observed)

    /**
     * 判定某个事件是否意味着「刚刚拍了一帧」。
     *
     * 纳入 [EV_CaptureOverflow] 是有意为之：它的语义是"拍摄发生了，但数据没能进存储"
     * （缓冲满、无存储卡等）。**曝光已经完成**，对补光同步来说这就是一次有效触发。
     * 漏掉它会导致连拍/缓冲未落盘时整段漏触发 —— 这比多触发一次更糟。
     */
    fun isShutterTrigger(code: Int): Boolean =
        code == EV_CaptureComplete ||
            code == EV_ObjectAdded ||
            code == EV_CaptureOverflow ||
            extraShutterCodes.contains(code)

    fun describe(code: Int): String = when (code) {
        EV_ObjectAdded -> "ObjectAdded(新文件)"
        EV_ObjectRemoved -> "ObjectRemoved"
        EV_StoreAdded -> "StoreAdded"
        EV_StoreRemoved -> "StoreRemoved"
        EV_DevicePropChanged -> "DevicePropChanged"
        EV_ObjectInfoChanged -> "ObjectInfoChanged"
        EV_DeviceInfoChanged -> "DeviceInfoChanged"
        EV_RequestObjectTransfer -> "RequestObjectTransfer"
        EV_StoreFull -> "StoreFull"
        EV_DeviceReset -> "DeviceReset"
        EV_StorageInfoChanged -> "StorageInfoChanged"
        EV_CaptureComplete -> "CaptureComplete(曝光已完成)"
        EV_CaptureOverflow -> "CaptureOverflow(曝光完成但未入存储)"
        EV_UnreportedStatus -> "UnreportedStatus"
        Nikon.CaptureOverflow -> "Nikon.CaptureOverflow(曝光完成但未入存储)"
        Nikon.ObjectAddedInSDRAM -> "Nikon.ObjectAddedInSDRAM(已进内存缓冲)"
        Nikon.AdvancedTransfer -> "Nikon.AdvancedTransfer"
        else -> {
            val vendor = if (code in 0x8000..0x8FFF || code in 0xC000..0xCFFF) "厂商私有" else "未知"
            "$vendor 0x%04X".format(code)
        }
    }

    /** 尼康私有事件码（与标准 0x4xxx 事件互相印证使用）。 */
    object Nikon {
        const val ObjectAddedInSDRAM = 0xC101
        const val CaptureOverflow = 0xC102
        const val AdvancedTransfer = 0xC103
    }
}
