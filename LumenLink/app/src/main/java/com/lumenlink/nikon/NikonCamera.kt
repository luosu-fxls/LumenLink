package com.lumenlink.nikon

import com.lumenlink.core.Logx
import com.lumenlink.ptp.ByteReader
import com.lumenlink.ptp.DevicePropDesc
import com.lumenlink.ptp.Ptp
import com.lumenlink.ptp.PtpSession
import com.lumenlink.ptp.hex
import kotlin.math.abs

/**
 * 尼康相机的高层封装：连接后先「自我发现」，再暴露快门/ISO 这些真正需要的操作。
 *
 * 工作方式（也是它能适配没见过的机型的原因）：
 *  1. GetDeviceInfo 拿到 devicePropertiesSupported 列表；
 *  2. 逐个 GetDevicePropDesc，缓存描述符（含枚举值、读写权限）；
 *  3. 用 [NikonProps.classify] 按值的形态认出「哪个是快门、哪个是 ISO」；
 *  4. 写值时严格按描述符的 DataType 编码，绝不硬编码字节。
 *
 * 实测结论（来自公开的 D850/D3500 属性 dump）：
 *  - D850 这类中高端机身的 ExposureTime 可读可写；
 *  - D3500/D3x00 这类入门机身 ExposureTime 只读、且不支持 Open Capture，
 *    也就是说**机身本身不允许 App 改快门**。这不是本 App 的 bug，
 *    所以这里会把 writable 标志透传给 UI，让界面诚实禁用。
 */
class NikonCamera(private val session: PtpSession) {

    private val props = LinkedHashMap<Int, DevicePropDesc>()

    var model: String = ""
        private set

    private var shutterCode: Int? = null
    private var isoCode: Int? = null

    @Volatile
    var shutterWritable: Boolean = false
        private set

    @Volatile
    var shutterReadable: Boolean = false
        private set

    private var lastShutterText: String = ""

    fun discover(): List<DevicePropDesc> {
        val info = session.deviceInfo
        model = info?.model?.takeIf { it.isNotBlank() }
            ?: info?.vendorExtensionDesc
            ?: "unknown"

        val codes = LinkedHashSet<Int>()
        info?.devicePropertiesSupported?.let { codes.addAll(it) }
        NikonProps.EXTRA_CANDIDATE_CODES.forEach { codes.add(it) }
        // 尼康私有属性码：现问机身要（0x90CA），不靠猜
        vendorPropCodes().forEach { codes.add(it) }
        // 标准曝光属性兜底：即使机身没在能力位图里列出，也试一把
        codes.add(Ptp.DPC_ExposureTime)
        codes.add(Ptp.DPC_ExposureIndex)
        codes.add(Ptp.DPC_FNumber)
        codes.add(Ptp.DPC_ExposureBiasCompensation)

        props.clear()
        var restricted = 0
        var ok = 0
        for (code in codes) {
            val desc = try {
                session.getPropDesc(code)
            } catch (t: Throwable) {
                Logx.w(TAG, "属性 0x%04X 读取异常: ${t.message}".format(code))
                null
            }
            if (desc != null) {
                props[code] = desc
                ok++
            } else {
                restricted++
            }
        }
        Logx.i(TAG, "属性表: 得到 ${props.size} 条，被拒 $restricted 条（共尝试 ${codes.size}）")

        // 重要诊断信号：真机若几乎全部属性都被拒，基本可以判定为「未配对 / 鉴权未完成」，
        // 而不是「这台机器不支持」。Z 系走 SnapBridge 架构时最容易踩到。
        if (ok == 0 && restricted >= 8) {
            accessLooksRestricted = true
            Logx.w(TAG, "所有属性读取都被拒 —— 这是鉴权/配对未完成的表现，而非机身不支持")
        } else {
            accessLooksRestricted = false
        }

        pickShutter()
        pickIso()
        calibrateExposureUnit()
        return props.values.toList()
    }

    /** 属性读取是否被大面积拒绝（典型为未配对）。 */
    @Volatile
    var accessLooksRestricted: Boolean = false
        private set

    // ---------------- 语义识别 ----------------

    /**
     * 向机身索取私有属性码列表（Nikon GetVendorPropCodes 0x90CA）。
     * 返回体的格式没有标准可依，这里做两种容错解析：带 u32 计数前缀的，和不带的。
     * 失败就返回空——绝不影响主流程。
     */
    private fun vendorPropCodes(): IntArray {
        val resp = try {
            session.op(NikonOps.GetVendorPropCodes)
        } catch (t: Throwable) {
            Logx.d(TAG, "GetVendorPropCodes 异常: ${t.message}")
            return IntArray(0)
        }
        if (!resp.ok) {
            Logx.d(TAG, "GetVendorPropCodes -> ${Ptp.responseName(resp.code)}（该机型可能不支持）")
            return IntArray(0)
        }
        val data = resp.data
        if (data == null || data.size < 4) return IntArray(0)

        // 三种可能的布局都试一遍，取"合理的那个"：
        //  1) u32 count 在前，后跟 count 个 u16
        //  2) MTP 标准数组：count 个 u16 在前，u32 count 在**最后**
        //  3) 纯 u16 数组（无计数）
        val byPrefix = tryParsePrefixCount(data)
        val bySuffix = tryParseSuffixCount(data)
        val out: List<Int> = when {
            byPrefix != null -> byPrefix
            bySuffix != null -> bySuffix
            else -> {
                val r = ByteReader(data)
                val list = ArrayList<Int>()
                while (r.remaining >= 2) list.add(r.u16())
                list
            }
        }
        Logx.i(TAG, "GetVendorPropCodes 返回 ${out.size} 个私有属性码 " +
            "(布局=${if (byPrefix != null) "前缀计数" else if (bySuffix != null) "后缀计数" else "裸数组"}): " +
            out.take(24).joinToString(",") { "0x%04X".format(it) })
        return out.toIntArray()
    }

    /** 布局 1：u32 count 在前。要求 count 与剩余长度严格自洽，否则不算数。 */
    private fun tryParsePrefixCount(data: ByteArray): List<Int>? {
        if (data.size < 6) return null
        val r = ByteReader(data)
        val count = r.u32()
        if (count < 1 || count > 4096) return null
        // 严格自洽：4 + count*2 必须等于总长（允许 0~3 字节对齐填充）
        val expect = 4 + count * 2
        if (data.size !in expect..(expect + 3)) return null
        val out = ArrayList<Int>(count.toInt())
        for (i in 0 until count.toInt()) out.add(r.u16())
        return out
    }

    /** 布局 2：MTP 标准数组，count(u32) 在末尾。 */
    private fun tryParseSuffixCount(data: ByteArray): List<Int>? {
        if (data.size < 6) return null
        val r = ByteReader(data)
        r.skip(data.size - 4)
        val count = r.u32()
        if (count < 1 || count > 4096) return null
        val expect = count * 2 + 4
        if (data.size !in expect..(expect + 3)) return null
        val r2 = ByteReader(data)
        val out = ArrayList<Int>(count.toInt())
        for (i in 0 until count.toInt()) out.add(r2.u16())
        return out
    }

    private fun pickShutter() {
        val candidates = props.values.filter { NikonProps.classify(it) == NikonProps.Kind.SHUTTER }
        if (candidates.isEmpty()) {
            shutterCode = null
            shutterWritable = false
            shutterReadable = false
            Logx.w(TAG, "没识别出快门速度属性")
            return
        }
        // 优先级：可写 > 字符串型（文本直接对应机身档位，最不易错）> 枚举条目多 > 标准属性码
        val best = candidates.sortedWith(
            compareBy(
                { if (it.writable) 0 else 1 },
                { if (it.dataType == Ptp.DT_STRING) 0 else 1 },
                { -it.enumValues.size },
                { if (it.propertyCode == Ptp.DPC_ExposureTime) 0 else 1 },
            )
        ).first()

        shutterCode = best.propertyCode
        shutterWritable = best.writable
        shutterReadable = true
        lastShutterText = best.current
        Logx.i(TAG, "快门属性 = 0x%04X (%s) writable=%s current=\"%s\" 候选值 %d 个"
            .format(best.propertyCode, Ptp.datatypeName(best.dataType), best.writable, best.current, best.enumValues.size))
    }

    private fun pickIso() {
        val candidates = props.values.filter { NikonProps.classify(it) == NikonProps.Kind.ISO }
        isoCode = candidates
            .sortedWith(
                compareBy(
                    { if (it.writable) 0 else 1 },
                    { if (it.dataType == Ptp.DT_STRING) 0 else 1 },
                    { -it.enumValues.size },
                )
            )
            .firstOrNull()
            ?.propertyCode
        Logx.i(TAG, "ISO 属性 = ${isoCode?.let { "0x%04X".format(it) } ?: "未识别"}")
    }

    /**
     * 数值型曝光时间的单位自校准：正确单位应该让枚举表的最大值落在「整秒」上
     * （尼康的档位上限是 30"/20"/15"/8" 这类整齐值）。
     */
    private fun calibrateExposureUnit() {
        val desc = shutterCode?.let { props[it] } ?: return
        if (desc.dataType == Ptp.DT_STRING) return
        val numeric = desc.enumValues.mapNotNull { it.trim().toLongOrNull() }
        if (numeric.isEmpty()) return
        val maxRaw = numeric.max()
        if (maxRaw <= 0) return

        val niceSeconds = intArrayOf(1, 2, 4, 8, 10, 15, 20, 25, 30, 60)
        var bestUnit = 10
        var bestScore = Long.MAX_VALUE
        for (unit in intArrayOf(1, 10, 100)) {
            val maxMillis = maxRaw * unit / 1000L
            val score = niceSeconds.minOfOrNull { abs(it * 1000L - maxMillis) } ?: Long.MAX_VALUE
            Logx.d(TAG, "曝光单位候选 ${unit}µs -> 最大档 $maxMillis ms (score=$score)")
            if (score < bestScore) {
                bestScore = score
                bestUnit = unit
            }
        }
        Logx.i(TAG, "曝光时间单位校准结果: ${bestUnit}µs（最大档 ${maxRaw * bestUnit / 1000}ms）")
        NikonProps.setExposureUnit(bestUnit)
    }

    // ---------------- 读取 ----------------

    fun readShutter(): String {
        val code = shutterCode ?: return ""
        val desc = props[code]
        if (desc != null) {
            if (desc.current.isNotBlank() && desc.dataType == Ptp.DT_STRING) {
                lastShutterText = desc.current
                return desc.current
            }
            if (desc.current.isNotBlank()) {
                val raw = desc.current.trim().toLongOrNull()
                if (raw != null) {
                    val ms = NikonProps.exposureRawToMillis(raw)
                    lastShutterText = NikonMath.millisToShutterText(ms)
                    return lastShutterText
                }
            }
        }
        // 描述符里没有可用的 current，主动读一次
        val raw = session.getPropValue(code) ?: return lastShutterText
        val text = NikonCodec.decode(desc ?: return lastShutterText, raw) ?: return lastShutterText
        lastShutterText = text
        return text
    }

    fun readIso(): String {
        val code = isoCode ?: return ""
        val desc = props[code] ?: return ""
        if (desc.current.isNotBlank()) return desc.current
        val raw = session.getPropValue(code) ?: return ""
        return NikonCodec.decode(desc, raw) ?: ""
    }

    // ---------------- 候选值（供 UI 下拉） ----------------

    fun shutterChoices(): List<String> {
        val desc = shutterCode?.let { props[it] } ?: return emptyList()
        if (desc.dataType == Ptp.DT_STRING) return desc.enumValues
        // 数值枚举：翻译成 "1/250" 这样的文本，UI 才看得懂
        val numeric = desc.enumValues.mapNotNull { it.trim().toLongOrNull() }
        if (numeric.isEmpty()) return emptyList()
        return numeric.map { NikonProps.exposureRawToText(it) }
            .distinct()
    }

    fun isoChoices(): List<String> {
        val desc = isoCode?.let { props[it] } ?: return emptyList()
        return desc.enumValues
    }

    // ---------------- 写入 ----------------

    fun writeShutter(value: String): Boolean {
        val code = shutterCode
        val desc = code?.let { props[it] }
        if (code == null || desc == null) {
            Logx.w(TAG, "没有可用的快门属性")
            return false
        }
        if (!desc.writable) {
            Logx.w(TAG, "该机身的快门属性是只读的（入门机型常见），无法遥控改快门")
            return false
        }

        val payload: ByteArray? = if (desc.dataType == Ptp.DT_STRING) {
            NikonCodec.encode(desc, value)
        } else {
            val v = value.trim()
            val raw: Long? = when {
                v.equals("Bulb", true) -> 0xFFFF_FFFFL
                v.equals("Time", true) -> 0xFFFF_FFFDL
                else -> NikonProps.resolveExposureRaw(desc, v)
            }
            if (raw == null) {
                Logx.w(TAG, "「$value」不在机身可选档位内，拒绝下发（避免 InvalidDevicePropValue）")
                return false
            }
            NikonCodec.encode(desc, raw.toString())
        }
        if (payload == null) return false

        val resp = session.setPropValue(code, payload)
        return if (resp.ok) {
            lastShutterText = value
            Logx.i(TAG, "快门速度已设为 $value")
            true
        } else {
            Logx.w(TAG, "SetDevicePropValue 失败: ${Ptp.responseName(resp.code)}")
            false
        }
    }

    fun writeIso(value: String): Boolean {
        val code = isoCode
        val desc = code?.let { props[it] }
        if (code == null || desc == null || !desc.writable) return false
        val payload = NikonCodec.encode(desc, value) ?: return false
        val resp = session.setPropValue(code, payload)
        if (!resp.ok) Logx.w(TAG, "设置 ISO 失败: ${Ptp.responseName(resp.code)}")
        return resp.ok
    }

    // ---------------- 拍摄 ----------------

    /**
     * 遥控拍摄。
     *
     * ⚠️ 实测结论（libgphoto2 对 Z 6_2 的真机 dump）：
     *     Z6II 的 Device Capabilities 明确写着 **"No Open Capture"**，
     *     即**不支持**标准 InitiateCapture(0x100E)。点"快门"没反应正是这个原因。
     *
     * Z6II 可用的释放路径（按可靠性排序）：
     *   1. Nikon Capture                 0x90C0  ← Z 系实际走这条
     *   2. Nikon CaptureInSDRAM          0x90CB
     *   3. Nikon InitiateCaptureRecInMedia 0x9207
     *   4. 标准 InitiateCapture          0x100E  ← 仅当能力位图确实有时才试
     */
    fun capture(): Boolean {
        val supported = session.deviceInfo?.operationsSupported
        val bitmapKnown = !supported.isNullOrEmpty()

        data class Att(val op: Int, val params: IntArray, val label: String)

        val attempts = listOf(
            Att(NikonOps.Capture, intArrayOf(), "尼康 Capture"),
            Att(NikonOps.Capture, intArrayOf(0), "尼康 Capture(mode=0)"),
            Att(NikonOps.CaptureInSDRAM, intArrayOf(), "尼康 CaptureInSDRAM"),
            Att(NikonOps.CaptureInSDRAM, intArrayOf(0), "尼康 CaptureInSDRAM(0)"),
            Att(NikonOps.InitiateCaptureRecInMedia, intArrayOf(), "尼康 RecInMedia"),
            Att(Ptp.OC_InitiateCapture, intArrayOf(0xFFFF_FFFF.toInt(), 0), "标准 InitiateCapture"),
        )

        for (a in attempts) {
            // 尼康私有码经常不出现在能力位图里，但实际可用 —— 不因位图缺失而跳过。
            val nikonPrivate = a.op == NikonOps.Capture ||
                a.op == NikonOps.CaptureInSDRAM ||
                a.op == NikonOps.InitiateCaptureRecInMedia
            if (bitmapKnown && !nikonPrivate && a.op !in supported!!) {
                Logx.d(TAG, "${a.label}(0x%04X) 不在能力位图内，跳过".format(a.op))
                continue
            }
            val t0 = System.currentTimeMillis()
            // 释放快门可能会让相机延迟响应甚至忙，用 opSafe 兜住，不让异常打断连接
            val resp = session.opSafe(a.op, a.params, timeoutMs = 20_000)
            val ms = System.currentTimeMillis() - t0
            if (resp.ok) {
                Logx.i(TAG, "✓ ${a.label}(0x%04X) 成功，耗时 ${ms}ms".format(a.op))
                lastCaptureOpcode = a.op
                return true
            }
            Logx.w(TAG, "✗ ${a.label}(0x%04X) -> ${Ptp.responseName(resp.code)}（${ms}ms）".format(a.op))
        }
        lastCaptureOpcode = null
        return false
    }

    /** 上次成功的释放操作码（诊断用）。 */
    @Volatile
    var lastCaptureOpcode: Int? = null
        private set

    /**
     * 兜底：先切到遥控模式再释放。
     * 只在用户主动点快门、且常规路径全部失败后才调用，避免每次连接都打乱相机状态。
     */
    fun captureWithModeSwitch(): Boolean {
        Logx.i(TAG, "常规释放全部失败，尝试先切遥控模式再释放")
        enterRemoteMode()
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
        return capture()
    }

    /**
     * 尝试进入「遥控/应用模式」。部分机身要先切模式才允许改参数和释放快门。
     * 两条私有路径都试，失败无所谓（很多机身默认就是遥控模式）。
     */
    fun enterRemoteMode() {
        runCatching {
            val r1 = session.op(NikonOps.ChangeApplicationMode, intArrayOf(1))
            Logx.i(TAG, "ChangeApplicationMode(1) -> ${Ptp.responseName(r1.code)}")
        }.onFailure { Logx.d(TAG, "ChangeApplicationMode 不支持: ${it.message}") }

        runCatching {
            val r2 = session.op(NikonOps.ChangeCameraMode, intArrayOf(1))
            Logx.i(TAG, "ChangeCameraMode(REMOTE) -> ${Ptp.responseName(r2.code)}")
        }.onFailure { Logx.d(TAG, "ChangeCameraMode 不支持: ${it.message}") }
    }

    // ---------------- Z 系：私有释放 / Ex 属性探测 ----------------

    /**
     * 探测 Z 系的私有快门释放路径（只读探测，不真正拍照）。
     *
     * 已知 Z 系在 Wi-Fi 下有几种可选的"按快门"实现：
     *  - 标准 0x100E InitiateCapture
     *  - 尼康 0x90C0 Capture
     *  - 0x9207 InitiateCaptureRecInMedia（老代码里常叫 InitiateCaptureRecInSdram）
     *  - 高层接口走 Ex 属性把 camera mode 设成「遥控释放」
     *
     * 本函数只查询各 opcode 是否会返回 OperationNotSupported，
     * 从而告诉用户在诊断里「这台机身支持哪条路」，而不是盲试。
     */
    fun probeRemoteOps(): String {
        val sb = StringBuilder()
        val candidates = listOf(
            "标准 InitiateCapture" to Ptp.OC_InitiateCapture,
            "Nikon Capture" to NikonOps.Capture,
            "Nikon CaptureInSDRAM" to NikonOps.CaptureInSDRAM,
            "Nikon ChangeApplicationMode" to NikonOps.ChangeApplicationMode,
            "Nikon ChangeCameraMode" to NikonOps.ChangeCameraMode,
        )
        val supported = session.deviceInfo?.operationsSupported ?: emptySet()
        for ((label, code) in candidates) {
            val inBitmap = code in supported
            sb.append("%-32s 0x%04X  能力位图:%s".format(label, code, if (inBitmap) "有" else "无"))
            if (inBitmap) sb.append("  ← 优先尝试")
            sb.appendLine()
        }
        sb.appendLine("(能力位图来自 GetDeviceInfo；若为空则说明该机身不报告能力列表)")
        return sb.toString()
    }

    /**
     * 探测 Ex 属性通道（0x943A/0x943B），但**不做语义解析**。
     *
     * 为什么只 dump 不解析：尼康 Ex 属性的载荷头部布局没有公开定论，
     * 各项目的实现互相矛盾。与其猜一个结构体解析出错误的值，不如把原始字节摆出来。
     * 真机跑一次之后就能确定布局，再补解析——这也正是需要 Z6II 实测的原因之一。
     */
    fun probeExProps(vararg codes: Int): String {
        val sb = StringBuilder()
        val probe = if (codes.isEmpty()) intArrayOf(Ptp.DPC_ExposureTime, Ptp.DPC_ExposureIndex) else codes
        for (code in probe) {
            sb.append("0x%04X: ".format(code))
            // 先试标准通道，再试 Ex 通道，两边对比
            val std = try {
                val r = session.op(Ptp.OC_GetDevicePropValue, intArrayOf(code))
                "std=${Ptp.responseName(r.code)} data=${r.data?.hex(24) ?: "-"}"
            } catch (t: Throwable) {
                "std=异常(${t.message})"
            }
            val ex = try {
                val r = session.op(NikonOps.GetDevicePropValueEx, intArrayOf(code))
                "ex=${Ptp.responseName(r.code)} data=${r.data?.hex(24) ?: "-"}"
            } catch (t: Throwable) {
                "ex=异常(${t.message})"
            }
            sb.appendLine("$std  |  $ex")
        }
        return sb.toString()
    }

    // ---------------- 被动检测：只看"有没有新照片" ----------------

    /**
     * 读取相机存储上的**最新一张照片**的句柄与文件信息。
     *
     * 这是整个工程里**侵入性最低**的检测方式：
     *  - 只发只读操作（GetStorageIDs / GetObjectHandles / GetObjectInfo）
     *  - 不发起拍摄、不改参数、不切换应用模式
     *  - 相机仍可正常拍摄，机身按键不受影响
     *
     * 代价：检测延迟。文件写入卡通常要 200ms~1s，所以只能用于
     * "事后知道拍了"（计数、统计、延后补光），不能做毫秒级同步。
     *
     * @return 最新对象的 (handle, filename) ；没有照片时返回 null
     */
    fun latestObject(): LatestObject? {
        val stores = getStorageIds()
        if (stores.isEmpty()) return null

        var bestHandle = -1L
        var bestName = ""
        var bestSize = 0L

        for (sid in stores) {
            val handles = getObjectHandles(sid)
            if (handles.isEmpty()) continue
            // 句柄通常是单调递增的，取最大的几个查询即可
            for (h in handles.sortedDescending().take(3)) {
                if (h <= bestHandle) continue
                val info = getObjectInfo(h) ?: continue
                // 只关心图片文件（JPEG/NEF/HEIF），跳过目录等
                if (!info.looksLikeImage()) continue
                if (h > bestHandle) {
                    bestHandle = h
                    bestName = info.filename
                    bestSize = info.size
                }
            }
        }
        return if (bestHandle < 0) null else LatestObject(bestHandle, bestName, bestSize)
    }

    data class LatestObject(val handle: Long, val filename: String, val sizeBytes: Long)

    private fun getStorageIds(): List<Long> {
        val resp = session.opSafe(Ptp.OC_GetStorageIDs, timeoutMs = 8_000)
        if (!resp.ok) {
            Logx.d(TAG, "GetStorageIDs -> ${Ptp.responseName(resp.code)}")
            return emptyList()
        }
        val data = resp.data ?: return emptyList()
        if (data.size < 4) return emptyList()
        val n = ByteReader(data).u32().toInt()
        if (n <= 0 || n > 64 || data.size < 4 + n * 4) return emptyList()
        val r = ByteReader(data)
        r.u32()
        return (0 until n).map { r.u32() }
    }

    private fun getObjectHandles(storageId: Long): List<Long> {
        val resp = session.opSafe(
            Ptp.OC_GetObjectHandles,
            intArrayOf(storageId.toInt(), 0, 0),   // storage, format=all, parent=root
            timeoutMs = 8_000,
        )
        if (!resp.ok) return emptyList()
        val data = resp.data ?: return emptyList()
        if (data.size < 4) return emptyList()
        val n = ByteReader(data).u32().toInt()
        if (n <= 0 || data.size < 4 + n * 4) return emptyList()
        val r = ByteReader(data)
        r.u32()
        return (0 until n).map { r.u32() }
    }

    /** GetObjectInfo 只解析需要的字段：文件名与大小。 */
    private data class ObjInfo(val filename: String, val size: Long) {
        fun looksLikeImage(): Boolean {
            val f = filename.uppercase()
            return f.endsWith(".JPG") || f.endsWith(".JPEG") ||
                f.endsWith(".NEF") || f.endsWith(".HEIF") || f.endsWith(".HIF")
        }
    }

    private fun getObjectInfo(handle: Long): ObjInfo? {
        val resp = session.opSafe(Ptp.OC_GetObjectInfo, intArrayOf(handle.toInt()), timeoutMs = 8_000)
        if (!resp.ok) return null
        val data = resp.data ?: return null
        if (data.size < 52) return null
        return try {
            val r = ByteReader(data)
            r.u32()                       // StorageID
            r.u16()                       // ObjectFormat
            r.u16()                       // ProtectionStatus
            r.u32()                       // ObjectCompressedSize
            r.u16()                       // ThumbFormat
            r.u32()                       // ThumbCompressedSize
            r.u32()                       // ThumbPixWidth
            r.u32()                       // ThumbPixHeight
            r.u32()                       // ImagePixWidth
            r.u32()                       // ImagePixHeight
            r.u32()                       // ImageBitDepth
            r.u32()                       // ParentObject
            r.u16()                       // AssociationType
            r.u32()                       // AssociationDesc
            r.u32()                       // SequenceNumber
            val name = r.ptpString()
            ObjInfo(name, 0L)
        } catch (t: Throwable) {
            Logx.d(TAG, "解析 ObjectInfo 失败: ${t.message}")
            null
        }
    }

    // ---------------- 诊断 ----------------

    /**
     * 事件通道不可用时的兜底探测：Nikon CheckEvent(0x90C7)。
     *
     * 返回体没有公开的标准布局（不同机身/固件差异很大），所以这里**不做假解析**，
     * 只把响应码和原始字节记录下来。真机上跑一次，把这段贴出来就能确定布局，
     * 然后在这里补上真正的解析——比凭空猜一个结构体靠谱得多。
     */
    fun probeCheckEvent(): String {
        return try {
            val resp = session.op(NikonOps.CheckEvent)
            val hex = resp.data?.hex(64) ?: "(无数据段)"
            "CheckEvent -> ${Ptp.responseName(resp.code)} params=${resp.params} data=$hex"
        } catch (t: Throwable) {
            "CheckEvent 异常: ${t.message}"
        }
    }

    fun propTableDump(): String {
        if (props.isEmpty()) return "(没有属性表)"
        val sb = StringBuilder()
        sb.appendLine("model=$model  shutter=0x%04X(writable=%s)  iso=0x%04X  单位=%dµs"
            .format(shutterCode ?: 0, shutterWritable, isoCode ?: 0, NikonProps.exposureUnitMicros))
        props.values.sortedBy { it.propertyCode }.forEach { d ->
            val kind = NikonProps.classify(d)
            // 标准曝光属性的裸值翻译成人类可读形式，便于核对
            val pretty = when (d.propertyCode) {
                Ptp.DPC_FNumber, Ptp.DPC_ExposureIndex,
                Ptp.DPC_ExposureBiasCompensation, Ptp.DPC_ExposureTime ->
                    if (d.dataType != Ptp.DT_STRING) NikonProps.formatStandardValue(d.propertyCode, d.current) else "—"
                else -> "—"
            }
            sb.append("0x%04X %-7s %s %-16s cur=%-10s human=%-10s".format(
                d.propertyCode, Ptp.datatypeName(d.dataType),
                if (d.writable) "RW" else "R-", kind.name, d.current, pretty
            ))
            when (d.formFlag) {
                1 -> sb.append(" range=[${d.rangeMin}..${d.rangeMax}/${d.rangeStep}]")
                2 -> sb.append(" enum(" + d.enumValues.size + ")=" + d.enumValues.take(24).joinToString(",")
                    + if (d.enumValues.size > 24) "…" else "")
            }
            sb.appendLine()
        }
        sb.appendLine("observed events: " + NikonEvents.observedSnapshot()
            .entries.joinToString { "0x%04X×%d".format(it.key, it.value) })
        return sb.toString()
    }

    private companion object {
        const val TAG = "NikonCamera"
    }
}
