package com.lumenlink.ptp

import java.io.Closeable

/** 相机主动推上来的事件（快门、属性变化、存储卡变化等）。 */
data class PtpEvent(
    val code: Int,
    val transactionId: Int,
    val params: List<Long>,
)

/** 一次 PTP 事务的完整结果（响应码 + 响应参数 + 可选数据段）。 */
data class PtpResponse(
    val code: Int,
    val params: List<Long> = emptyList(),
    val data: ByteArray? = null,
) {
    val ok: Boolean get() = code == Ptp.RC_OK
    override fun toString(): String =
        "${Ptp.responseName(code)} params=$params data=${data?.size ?: 0}B"
}

/**
 * 传输层抽象：屏蔽 PTP/IP(TCP) 与 PTP-over-USB(bulk) 的差异。
 *
 * 实现约定：一次 [request] 内部完成「命令段 -> 数据段 -> 响应段」的完整事务，
 * 因此上层 [PtpSession] 不需要关心分帧细节。事件统一通过 [beginEventPump] 回调。
 */
interface PtpTransport : Closeable {

    val label: String

    /** 建立连接（含 PTP/IP 的握手 / USB 的接口声明）。阻塞调用，需在工作线程执行。 */
    fun connect(timeoutMs: Int = 10_000)

    /**
     * 执行一次 PTP 操作。
     * @param dataOut 非 null 表示存在 DataOut 段（例如 SetDevicePropValue）
     * @return 相机响应；响应数据段在 [PtpResponse.data]
     */
    fun request(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 10_000,
    ): PtpResponse

    fun beginEventPump(onEvent: (PtpEvent) -> Unit, onError: (Throwable) -> Unit)

    fun endEventPump()

    /**
     * 事件通道是否真的建立了。
     * PTP/IP 的第二条 TCP 有可能被机身拒绝（只允许单连接的机型常见），
     * 此时连接本身仍然可用（能读能写参数），只是拿不到推送事件 —— 上层据此降级到音频触发。
     */
    val pushEventsActive: Boolean get() = false
}

/** 传输层可恢复错误（超时、断链），上层据此决定重连。 */
class PtpIoException(message: String, cause: Throwable? = null) : Exception(message, cause)
