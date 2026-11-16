package com.lumenlink.ptp

import android.net.Network
import com.lumenlink.core.Logx
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTP/IP 传输（PIMA 15740 Annex F），尼康 Wi-Fi / SnapBridge 走的都是这条路径。
 *
 * 连接流程：
 *  1. TCP 连到相机 15740 端口
 *  2. InitCommandRequest  (GUID + 友好名 + 协议版本)
 *  3. 读 InitCommandAck   (得到 connectionNumber)
 *  4. 另开一条 TCP，发 InitEventRequest(connectionNumber) —— 这是事件通道
 *  5. 之后：命令/数据/响应都在第 1 条连接上，事件只在第 2 条连接上
 *
 * 关于 [network]：如果用 WifiNetworkSpecifier 连接到相机热点，该网络不是默认网络，
 * 必须通过 network.socketFactory 建 socket，否则会走到蜂窝/家用 Wi-Fi 上，必然连不上。
 */
class PtpIpTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val friendlyName: String = "LumenLink",
    private val clientGuid: ByteArray = newGuid(),
    private val network: Network? = null,
    private val protocolVersion: Long = 0x0001_0000L,
) : PtpTransport {

    override val label: String get() = "PTP/IP $host:$port"

    private var cmdSocket: Socket? = null
    private var evtSocket: Socket? = null
    private var cmdOut: OutputStream? = null
    private var cmdIn: InputStream? = null
    private var evtIn: InputStream? = null

    private var connectionNumber: Long = 0
    private var transactionId: Int = 0

    /** 只保护写操作；读操作各连接独立，不能共用锁（见 [readPacket] 的注释）。 */
    private val writeLock = Any()

    private val pumpRunning = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    @Volatile
    private var eventHandler: ((PtpEvent) -> Unit)? = null

    @Volatile
    private var errorHandler: ((Throwable) -> Unit)? = null

    var responderName: String = ""
        private set

    override fun connect(timeoutMs: Int) {
        connectStage1(timeoutMs)
        connectStage2(timeoutMs)
    }

    /**
     * 阶段 1：TCP 连接 + InitCommandRequest/InitCommandAck 握手。
     *
     * 单独拆出来是为了做**分层验证**：
     * 用户可以在只完成握手、尚未发 OpenSession 的状态下测试相机是否还能正常拍摄，
     * 从而精确判断"是哪一步让相机进入受控状态"。
     */
    fun connectStage1(timeoutMs: Int) {
        if (cmdSocket != null) return
        val sock = try {
            openCommandSocket(timeoutMs)
        } catch (t: java.net.ConnectException) {
            throw PtpIoException(
                "TCP 连接被拒绝（$host:$port）。请确认：\n" +
                    "1) 手机已连上相机热点，且该热点是当前默认网络；\n" +
                    "2) 相机 Wi-Fi 处于「连接中/待连接」状态（不是睡眠）；\n" +
                    "3) IP 地址正确（尼康 AP 模式通常是 192.168.1.1）。\n" +
                    "原始错误: ${t.message}",
                t
            )
        } catch (t: java.net.SocketTimeoutException) {
            throw PtpIoException(
                "TCP 连接超时（$host:$port）。相机可能休眠了，半按一下快门唤醒后重试。",
                t
            )
        }

        Logx.i(TAG, "TCP 已连接，开始 PTP/IP 握手 as \"$friendlyName\"")
        cmdSocket = sock
        cmdOut = sock.getOutputStream()
        cmdIn = sock.getInputStream()

        doHandshake(timeoutMs)
        transactionId = 1
        stage1Done = true
    }

    /**
     * 阶段 2：建立事件通道（InitEventRequest）。
     * 有些机身在这一步就会开始改变行为，所以也要单独可测。
     */
    fun connectStage2(timeoutMs: Int) {
        if (!stage1Done) throw PtpIoException("请先完成阶段1（TCP 握手）")
        if (_pushEvents) return
        establishEventChannel(timeoutMs)
    }

    /** 阶段1/2 是否已完成。 */
    @Volatile
    var stage1Done: Boolean = false
        private set

    /** 阶段2 之后 相机是否已进入"受控"状态（本类无法判断，由用户肉眼确认）。 */
    val handshakeDone: Boolean get() = stage1Done


    private fun openCommandSocket(timeoutMs: Int): Socket =
        (network?.socketFactory?.createSocket() ?: Socket()).apply {
            tcpNoDelay = true
            keepAlive = true
            // 相机热点没有外网，默认网络可能是蜂窝；必须绑到我们指定的网络接口上。
            network?.bindSocket(this)
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }

    private fun doHandshake(timeoutMs: Int) {
        // --- InitCommandRequest ---
        val body = ByteWriter(64)
            .raw(clientGuid)
            .ptpString(friendlyName)
            .u32(protocolVersion)
            .toByteArray()
        writePacket(Ptp.PKT_INIT_COMMAND_REQUEST, body)

        val (type, payload) = readPacket()
        when (type) {
            Ptp.PKT_INIT_COMMAND_ACK -> {
                val r = ByteReader(payload)
                connectionNumber = r.u32()
                r.skip(16) // responder GUID
                responderName = r.ptpString()
                val ver = if (r.remaining >= 4) r.u32() else 0
                Logx.i(TAG, "handshake ok: conn=$connectionNumber cam=\"$responderName\" ver=0x%08X".format(ver))
            }
            Ptp.PKT_INIT_FAIL -> {
                // 注意：按 PTP/IP 文档，Init_Fail 也可能由 Responder 发出；
                // 这里统一按「被拒绝」处理，并给出最可能的原因。
                val r = ByteReader(payload)
                val reason = if (payload.size >= 4) r.u32() else -1L
                throw PtpIoException(
                    "相机拒绝握手 (Init_Fail, reason=0x%08X)".format(reason) + "\n" +
                        pairingHint(reason)
                )
            }
            else -> throw PtpIoException("握手阶段收到意外包类型 $type")
        }

        // --- InitEventRequest（第 2 条连接） ---
        // 这一步失败**不能**让整个连接失败：有些机身只允许单连接，
        // 拒绝事件通道但仍允许读写参数。降级到音频触发即可。
        try {
            val evtSock = (network?.socketFactory?.createSocket() ?: Socket()).apply {
                tcpNoDelay = true
                network?.bindSocket(this)
                connect(InetSocketAddress(host, port), timeoutMs)
                soTimeout = timeoutMs
            }
            evtSocket = evtSock
            val evtOut = evtSock.getOutputStream()
            evtIn = evtSock.getInputStream()
            val evtBody = ByteWriter(8).u32(connectionNumber).toByteArray()
            writeRawPacket(evtOut, Ptp.PKT_INIT_EVENT_REQUEST, evtBody)

            val (etype, epayload) = readPacketFrom(evtIn!!)
            if (etype != Ptp.PKT_INIT_EVENT_ACK) {
                throw PtpIoException("事件通道握手失败，包类型=$etype 载荷=${epayload.hex(32)}")
            }
            _pushEvents = true
            Logx.i(TAG, "event channel ready")
        } catch (t: Throwable) {
            _pushEvents = false
            Logx.w(TAG, "事件通道不可用（降级为仅参数控制 + 音频触发）: ${t.message}")
            try {
                evtSocket?.close()
            } catch (_: Throwable) {
            }
            evtSocket = null
            evtIn = null
        }

        // 事务号从 1 开始
        transactionId = 1
    }

    /**
     * 建立事件通道（第 2 条 TCP）。
     * 失败不影响主通路：有些机身只允许单连接。
     */
    private fun establishEventChannel(timeoutMs: Int) {
        try {
            val evtSock = (network?.socketFactory?.createSocket() ?: Socket()).apply {
                tcpNoDelay = true
                network?.bindSocket(this)
                connect(InetSocketAddress(host, port), timeoutMs)
                soTimeout = timeoutMs
            }
            evtSocket = evtSock
            val evtOut = evtSock.getOutputStream()
            evtIn = evtSock.getInputStream()
            val evtBody = ByteWriter(8).u32(connectionNumber).toByteArray()
            writeRawPacket(evtOut, Ptp.PKT_INIT_EVENT_REQUEST, evtBody)

            val (etype, epayload) = readPacketFrom(evtIn!!)
            if (etype != Ptp.PKT_INIT_EVENT_ACK) {
                throw PtpIoException("事件通道握手失败，包类型=$etype 载荷=${epayload.hex(32)}")
            }
            _pushEvents = true
            Logx.i(TAG, "event channel ready")
        } catch (t: Throwable) {
            _pushEvents = false
            Logx.w(TAG, "事件通道不可用（降级为仅参数控制 + 音频触发）: ${t.message}")
            try {
                evtSocket?.close()
            } catch (_: Throwable) {
            }
            evtSocket = null
            evtIn = null
        }
    }

    @Volatile
    private var _pushEvents = false

    override val pushEventsActive: Boolean get() = _pushEvents

    override fun request(
        opcode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        timeoutMs: Int,
    ): PtpResponse = synchronized(writeLock) {
        val out = cmdOut ?: throw PtpIoException("连接已关闭")
        val tid = nextTransactionId()

        val body = ByteWriter(32 + params.size * 4 + 8)
            .u32(if (dataOut != null) Ptp.DATA_PHASE_OUT.toLong() else Ptp.DATA_PHASE_NONE.toLong())
            .u16(opcode)
            .u32(tid.toLong())
        for (p in params) body.u32(p.toLong() and 0xFFFF_FFFFL)

        Logx.d(TAG, "-> op=${Ptp.opcodeName(opcode)}(0x%04X) tid=$tid params=%s dataOut=%dB"
            .format(opcode, params.joinToString(",") { "0x%X".format(it) }, dataOut?.size ?: 0))

        writeRawPacket(out, Ptp.PKT_OPERATION_REQUEST, body.toByteArray())

        if (dataOut != null) {
            sendDataPhase(out, tid, dataOut)
        }

        readResponseLoop(tid, timeoutMs)
    }

    private fun sendDataPhase(out: OutputStream, tid: Int, data: ByteArray) {
        // StartData: tid + total length(u64)
        val start = ByteWriter(16).u32(tid.toLong()).u64(data.size.toLong()).toByteArray()
        writeRawPacket(out, Ptp.PKT_START_DATA, start)

        var off = 0
        while (off < data.size) {
            val n = minOf(CHUNK, data.size - off)
            val head = ByteWriter(8).u32(tid.toLong()).toByteArray()
            val chunk = head + data.copyOfRange(off, off + n)
            writeRawPacket(out, Ptp.PKT_DATA, chunk)
            off += n
        }
        val end = ByteWriter(8).u32(tid.toLong()).toByteArray()
        writeRawPacket(out, Ptp.PKT_END_DATA, end)
    }

    private fun readResponseLoop(tid: Int, timeoutMs: Int): PtpResponse {
        val sock = cmdSocket ?: throw PtpIoException("连接已关闭")
        sock.soTimeout = timeoutMs
        val acc = java.io.ByteArrayOutputStream()
        var totalExpected = -1L

        while (true) {
            val (type, payload) = readPacket()
            when (type) {
                Ptp.PKT_OPERATION_RESPONSE -> {
                    val r = ByteReader(payload)
                    val rc = r.u16()
                    val rtid = r.u32().toInt()
                    val rparams = ArrayList<Long>()
                    while (r.remaining >= 4) rparams.add(r.u32())
                    val data = if (acc.size() > 0) acc.toByteArray() else null
                    Logx.d(TAG, "<- resp=${Ptp.responseName(rc)}(0x%04X) tid=$rtid data=%dB"
                        .format(rc, data?.size ?: 0))
                    if (rtid != tid) {
                        Logx.w(TAG, "响应事务号不匹配: 期望 $tid 实际 $rtid（继续信任响应）")
                    }
                    return PtpResponse(rc, rparams, data)
                }
                Ptp.PKT_START_DATA -> {
                    val r = ByteReader(payload)
                    r.u32() // tid
                    totalExpected = r.u64()
                    Logx.d(TAG, "  data-in start, expect $totalExpected B")
                }
                Ptp.PKT_DATA, Ptp.PKT_END_DATA -> {
                    val r = ByteReader(payload)
                    r.u32() // tid
                    acc.write(r.bytes(r.remaining))
                }
                Ptp.PKT_EVENT -> {
                    // 少数机身会在命令通道上顺手塞事件
                    dispatchEvent(parseEvent(payload))
                }
                else -> Logx.w(TAG, "命令通道收到未知包类型 $type (${payload.size}B)，忽略")
            }
        }
    }

    override fun beginEventPump(onEvent: (PtpEvent) -> Unit, onError: (Throwable) -> Unit) {
        eventHandler = onEvent
        errorHandler = onError
        if (!_pushEvents) {
            Logx.i(TAG, "事件通道未建立，跳过事件泵")
            return
        }
        // 事件通道要长时间阻塞等待，必须清掉握手时用的读超时，
        // 否则 SocketTimeoutException 会把事件泵打断。
        try {
            evtSocket?.soTimeout = 0
        } catch (t: Throwable) {
            Logx.w(TAG, "清除事件通道读超时失败: ${t.message}")
        }
        if (!pumpRunning.compareAndSet(false, true)) return
        val t = Thread({
            val input = evtIn
            if (input == null) {
                pumpRunning.set(false)
            } else {
                try {
                    while (pumpRunning.get()) {
                        // 连续读同一个连接的所有包，中途绝不回到外层循环判条件，
                        // 避免在两次读之间被漏掉的"半包"状态破坏流。
                        val (type, payload) = readPacketFrom(input)
                        if (type == Ptp.PKT_EVENT) {
                            dispatchEvent(parseEvent(payload))
                        } else {
                            Logx.d(TAG, "事件通道收到非事件包 type=$type")
                        }
                    }
                } catch (t: Throwable) {
                    // 关闭时 readFully 必然抛"连接被对端关闭"，属正常收尾
                    if (pumpRunning.get()) {
                        Logx.w(TAG, "事件通道中断: ${t.message}")
                        errorHandler?.invoke(t)
                    }
                } finally {
                    pumpRunning.set(false)
                }
            }
        }, "ptp-ip-events")
        t.isDaemon = true
        pumpThread = t
        t.start()
    }

    override fun endEventPump() {
        pumpRunning.set(false)
        pumpThread = null
        try {
            evtSocket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun dispatchEvent(e: PtpEvent) {
        try {
            eventHandler?.invoke(e)
        } catch (t: Throwable) {
            Logx.e(TAG, "事件处理异常", t)
        }
    }

    private fun parseEvent(payload: ByteArray): PtpEvent {
        val r = ByteReader(payload)
        val code = r.u16()
        val tid = r.u32().toInt()
        val params = ArrayList<Long>()
        while (r.remaining >= 4) params.add(r.u32())
        return PtpEvent(code, tid, params)
    }

    private fun nextTransactionId(): Int = synchronized(this) {
        val v = transactionId
        transactionId = if (transactionId >= 0xFFFF_FFFE) 1 else transactionId + 1
        v
    }

    // ---------------- 组帧 ----------------

    private fun writePacket(type: Int, body: ByteArray) {
        val out = cmdOut ?: throw PtpIoException("连接已关闭")
        writeRawPacket(out, type, body)
    }

    private fun writeRawPacket(out: OutputStream, type: Int, body: ByteArray) {
        val total = body.size + 8
        val head = ByteWriter(8).u32(total.toLong()).u32(type.toLong()).toByteArray()
        out.write(head)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /**
     * 读命令通道上的一个包。
     *
     * 这里**不能**用共享锁：命令通道的读只会发生在「已发出请求、等待响应」的窗口内，
     * 且 [request] 已经对该窗口做了串行化，天然没有并发读。
     * 事件通道是独立的 InputStream，绝不能被这把锁挡在后面 ——
     * 否则事件泵阻塞读时会永久持有锁，命令通道随后的每一次读取都会死锁。
     */
    private fun readPacket(): Pair<Int, ByteArray> {
        val input = cmdIn ?: throw PtpIoException("连接已关闭")
        return readPacketFrom(input)
    }

    private fun readPacketFrom(input: InputStream): Pair<Int, ByteArray> {
        val head = readFully(input, 8)
        val r = ByteReader(head)
        val len = r.u32()
        val type = r.u32().toInt()
        if (len < 8 || len > MAX_PACKET) {
            throw PtpIoException("非法包长度 $len（type=$type）")
        }
        val body = if (len > 8) readFully(input, (len - 8).toInt()) else ByteArray(0)
        return type to body
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val got = input.read(buf, off, n - off)
            if (got < 0) throw PtpIoException("连接被对端关闭（已读 $off/$n 字节）")
            off += got
        }
        return buf
    }

    /**
     * Init_Fail 的原因归因。
     *
     * 尼康 Z 系（含 Z6II）的 Wi-Fi 网络配置文件**按客户端 GUID 配对**：
     * 一旦某个 GUID 配对成功，其他 GUID 会在握手阶段直接被 Init_Fail 拒绝。
     * 所以 reason=1 最常见的原因是「这个 GUID 不是被配对的那个」，
     * 其次才是「已有另一个客户端占着相机」。
     */
    private fun pairingHint(reason: Long): String = when {
        reason == 1L ->
            "最可能的原因（按概率排序）：\n" +
                "  a) 该相机已与另一个客户端配对/连接 —— 退出尼康官方 App 或另一台设备；\n" +
                "  b) 相机网络配置文件里配对的是「另一个 GUID」，而本机 GUID 变了。\n" +
                "     → 相机上：网络菜单 → 网络设定 → 删除该配置文件后重建，然后重连即可重新配对。\n" +
                "     （本机 GUID 是持久化的，正常不会变；除非清过应用数据或重装）\n" +
                "  c) 相机当前处于回放/菜单界面，部分机型要求切回拍摄状态。"
        else -> "请检查相机端网络状态；若反复失败，重建相机上的网络配置文件。"
    }

    override fun close() {        endEventPump()
        // 退出前礼貌地关会话（失败无所谓，机身侧也会超时清理）
        try {
            if (cmdOut != null) request(Ptp.OC_CloseSession, timeoutMs = 1500)
        } catch (_: Throwable) {
        }
        listOf(cmdSocket, evtSocket).forEach {
            try {
                it?.close()
            } catch (_: IOException) {
            }
        }
        cmdSocket = null
        evtSocket = null
        cmdIn = null
        cmdOut = null
        evtIn = null
        Logx.i(TAG, "closed")
    }

    companion object {
        private const val TAG = "PtpIp"
        const val DEFAULT_PORT = 15740
        private const val CHUNK = 64 * 1024
        private const val MAX_PACKET = 64 * 1024 * 1024

        /** 生成一个稳定的随机 GUID；某些机型要求两次连接用同一 GUID。 */
        fun newGuid(): ByteArray {
            val g = ByteArray(16)
            java.util.Random().nextBytes(g)
            // 版本 4 UUID 的位标记，纯属礼貌
            g[6] = ((g[6].toInt() and 0x0F) or 0x40).toByte()
            g[8] = ((g[8].toInt() and 0x3F) or 0x80).toByte()
            return g
        }
    }
}
