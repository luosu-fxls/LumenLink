package com.lumenlink.ptp

import com.lumenlink.core.Logx
import java.util.concurrent.atomic.AtomicInteger

/**
 * PTP 会话层：负责 OpenSession / 属性读写 / 事件分发这些「协议语义」，
 * 把传输层的分帧细节完全隔离掉。
 *
 * 线程安全策略：一个会话背后只有一条命令/数据通道，**同一时刻只允许一个事务**。
 * 所有 request 都必须经由 [io] 这个唯一锁。这样做的代价是并发度低，
 * 但换来的是「事务号与响应绝不会错配」——这在真机联调时省掉大量玄学问题。
 * 所有方法都是阻塞式的，调用方必须自己在 IO 线程上调用。
 */
class PtpSession(val transport: PtpTransport) {

    /** 唯一的 IO 闸门。 */
    private val io = Any()

    /**
     * 事件通道与命令通道是两条独立的 TCP/管道，可以真并行；
     * 但事件回调里若再发 PTP 请求就会和主流程抢锁。用这个标志在事件线程上
     * 做「只读小请求」是危险的，所以事件回调里一律不许发请求（由上层保证）。
     */
    val transactionsInFlight = AtomicInteger(0)

    var deviceInfo: DeviceInfo? = null
        private set

    /** 相机事件（快门、属性变更……）的观察者。 */
    @Volatile
    var eventListener: ((PtpEvent) -> Unit)? = null

    /** 会话是否已建立（OpenSession 成功）。 */
    @Volatile
    var sessionOpen: Boolean = false
        private set

    /** 事件通道是否可用；不可用时上层应降级到音频触发。 */
    val pushEventsActive: Boolean get() = transport.pushEventsActive

    private val sessionId = 1

    /**
     * 唯一的出站通道：所有事务串行化，并统一记账，便于诊断"到底谁在占着通道"。
     */
    private fun tx(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 10_000,
    ): PtpResponse = synchronized(io) {
        transactionsInFlight.incrementAndGet()
        try {
            transport.request(opcode, params, dataOut, timeoutMs)
        } finally {
            transactionsInFlight.decrementAndGet()
        }
    }

    fun connectAndOpen(timeoutMs: Int = 10_000) {
        transport.connect(timeoutMs)
        deviceInfo = try {
            val resp = tx(Ptp.OC_GetDeviceInfo, timeoutMs = timeoutMs)
            if (resp.ok) resp.data?.let { DeviceInfo.parse(it) } else {
                Logx.w(TAG, "GetDeviceInfo 返回 ${Ptp.responseName(resp.code)}")
                null
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "GetDeviceInfo 失败: ${t.message}")
            null
        }
        openSession()
        transport.beginEventPump(
            onEvent = { e ->
                Logx.d(TAG, "event 0x%04X tid=%d params=%s".format(e.code, e.transactionId, e.params))
                // 注意：本回调运行在事件线程上，**不允许**在这里发 PTP 请求，
                // 否则会与主流程抢 io 锁并可能把事务序列搅乱。上层只做轻量状态更新。
                eventListener?.invoke(e)
            },
            onError = { t -> Logx.w(TAG, "事件通道异常: ${t.message}") },
        )
    }

    fun openSession() {
        val resp = tx(Ptp.OC_OpenSession, intArrayOf(sessionId))
        when (resp.code) {
            Ptp.RC_OK -> {
                sessionOpen = true
                Logx.i(TAG, "OpenSession ok")
            }
            Ptp.RC_SessionAlreadyOpen -> {
                // 会话已经开着——这在本工程里是**正常**情况：
                // 端点探测阶段会先发一次 OpenSession 来验证通路，
                // 或者上一次连接被强杀后相机侧会话尚未超时清理。
                // 直接沿用即可，不要当成错误。
                sessionOpen = true
                Logx.i(TAG, "OpenSession: 会话已存在（沿用，通常由端点探测或上次未正常关闭导致）")
            }
            else -> {
                sessionOpen = false
                Logx.e(TAG, "OpenSession 失败: ${Ptp.responseName(resp.code)}")
                throw PtpIoException("OpenSession 失败: ${Ptp.responseName(resp.code)}")
            }
        }
    }

    fun close() {
        sessionOpen = false
        transport.endEventPump()
        try {
            transport.close()
        } catch (t: Throwable) {
            Logx.w(TAG, "关闭传输层异常: ${t.message}")
        }
    }

    // ---------------- 属性访问 ----------------

    fun getPropDesc(code: Int): DevicePropDesc? {
        val resp = tx(Ptp.OC_GetDevicePropDesc, intArrayOf(code))
        if (!resp.ok) {
            Logx.d(TAG, "GetDevicePropDesc(0x%04X) -> %s".format(code, Ptp.responseName(resp.code)))
            return null
        }
        val data = resp.data ?: return null
        return DevicePropDesc.parse(data)
    }

    fun getPropValue(code: Int): ByteArray? {
        val resp = tx(Ptp.OC_GetDevicePropValue, intArrayOf(code))
        if (!resp.ok) {
            Logx.d(TAG, "GetDevicePropValue(0x%04X) -> %s".format(code, Ptp.responseName(resp.code)))
            return null
        }
        return resp.data
    }

    /**
     * 写属性。PTP 要求 DataOut 段的数据类型必须与属性描述符里的 DataType 一致，
     * 所以 [payload] 必须由调用方按描述符编码好（见 NikonCodec）。
     */
    fun setPropValue(code: Int, payload: ByteArray): PtpResponse =
        tx(Ptp.OC_SetDevicePropValue, intArrayOf(code), payload)

    /** 通用操作调用（私有扩展也走这里）。 */
    fun op(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 10_000,
    ): PtpResponse = tx(opcode, params, dataOut, timeoutMs)

    /**
     * 长操作（例如释放快门）：期间相机会断开或延迟响应，所以：
     *  1. 自定义超时；
     *  2. 失败时**不抛异常**，把错误码交给调用方判断 —— 释放快门返回
     *     DeviceBusy / 超时很常见，不该中断整个连接。
     */
    fun opSafe(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 20_000,
    ): PtpResponse = try {
        tx(opcode, params, dataOut, timeoutMs)
    } catch (t: Throwable) {
        Logx.w(TAG, "op 0x%04X 未正常返回: ${t.message}".format(opcode))
        PtpResponse(Ptp.RC_UNDEFINED)
    }

    /**
     * 枚举机身支持的全部属性并逐个拉取描述符。
     * 这是「机型无关」的核心手段：不猜属性码，直接问机身。
     */
    fun enumerateProps(onEach: (DevicePropDesc) -> Unit = {}): List<DevicePropDesc> {
        val codes = deviceInfo?.devicePropertiesSupported ?: emptySet()
        val out = ArrayList<DevicePropDesc>(codes.size)
        for (code in codes) {
            val desc = try {
                getPropDesc(code)
            } catch (t: Throwable) {
                Logx.w(TAG, "属性 0x%04X 读取异常: ${t.message}".format(code))
                null
            }
            if (desc != null) {
                onEach(desc)
                out.add(desc)
            }
        }
        Logx.i(TAG, "枚举属性完成: ${out.size}/${codes.size}")
        return out
    }

    companion object {
        private const val TAG = "PtpSession"
    }
}
