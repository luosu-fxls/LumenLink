package com.lumenlink.ptp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.lumenlink.core.Logx
import com.lumenlink.ptp.hex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTP over USB（PIMA 15740 §13，USB Still Image 类）——**不需要 root**。
 *
 * Android 上能这么干的原因：PTP 相机在 USB 上就是一个普通的 vendor/Still-Image 类设备，
 * 它的三条 bulk 管道（命令/数据、事件）对用户态完全开放，[UsbDeviceConnection.bulkTransfer]
 * 直接读写即可，不涉及任何内核驱动。
 *
 * 容器头格式（注意与 PTP/IP 不同！）：
 *   Length(u32) | Type(u16) | Code(u16) | TransactionID(u32)  = 12 字节
 *   Type: 1=命令 2=数据 3=响应 4=事件
 *
 * 已知坑（真机必看 Logx 里的端点清单）：
 *  - 部分机型/固件把事件端点排在数据端点前面，因此不能靠"第几个 IN"盲猜；
 *    这里按 bulk IN 的端点地址升序取第一个作数据口、第二个作事件口，并允许手工覆盖。
 *  - 某些机身 openDevice 返回 null：多半是被系统 MTP 服务或另一个 App 抢占了，
 *    必须先在系统里断开/卸载对应 App，或拔插一次。
 */
class PtpUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    /** 手工指定端点，null 表示自动探测 */
    private val overrideDataInAddr: Int? = null,
    private val overrideEventAddr: Int? = null,
) : PtpTransport {

    override val label: String get() = "USB PTP ${device.manufacturerName ?: "?"} ${device.productName ?: ""}"

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epCmdOut: UsbEndpoint? = null
    private var epDataIn: UsbEndpoint? = null
    private var epDataOut: UsbEndpoint? = null
    private var epEvent: UsbEndpoint? = null

    private var transactionId: Int = 1
    private var timeoutMs: Int = 5000

    private val pumpRunning = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    @Volatile private var eventHandler: ((PtpEvent) -> Unit)? = null
    @Volatile private var errorHandler: ((Throwable) -> Unit)? = null

    val isOpen: Boolean get() = connection != null

    override fun connect(timeoutMs: Int) {
        this.timeoutMs = timeoutMs
        val conn = usbManager.openDevice(device)
            ?: throw PtpIoException(
                "openDevice 返回 null：设备被系统或其他 App 占用（请拔插一次，或关掉相机类/MTP 相关 App）"
            )
        connection = conn

        val iface = pickInterface(device)
            ?: run {
                conn.close()
                throw PtpIoException("没找到带 bulk 端点的接口，设备描述: ${describeDevice(device)}")
            }
        usbInterface = iface
        Logx.i(TAG, "interface id=${iface.id} class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol} eps=${iface.endpointCount}")

        // ------------------------------------------------------------------
        // 关键：先 **不强制** 认领接口。
        //
        // claimInterface(force=true) 会要求内核解绑已绑定的驱动。在 MIUI/HyperOS 上，
        // 相机常被系统 MTP 服务先一步认领，强制解绑后内核 USB 状态机会处于半残缺状态：
        // 表现正是我们遇到的 —— 写命令容器返回 -1、读响应立即返回 -1、
        // 命令似乎发出但没人收走响应（端点被 halt）。
        //
        // 所以顺序改为：先温和认领；失败再强制。并且认领后显式重设一次接口设置，
        // 把端点状态拉回干净的一次性配置。
        // ------------------------------------------------------------------
        var claimed = conn.claimInterface(iface, false)
        val usedForce = !claimed
        if (usedForce) {
            Logx.w(TAG, "温和 claimInterface 失败，改用 force=true（可能被系统 MTP 服务占用）")
            claimed = conn.claimInterface(iface, true)
        }
        if (!claimed) {
            conn.close()
            connection = null
            throw PtpIoException(
                "claimInterface 失败（温和与强制都失败）：接口被其他进程占用。\n" +
                    "系统「照片」/「文件管理」或另一个相机 App 抢占了相机，先全部关闭后再插拔一次。"
            )
        }
        Logx.i(TAG, "claimInterface 成功 (force=$usedForce)")

        normalizeInterface(conn, iface)

        bindEndpoints(iface, conn)
        transactionId = 1

        // USB 在 SET_CONFIGURATION / SET_INTERFACE 之后需要一小段稳定期
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }

        // 探测本身必须完全兜住异常：探测失败只应导致"用默认组合"，绝不能让整个连接挂掉。
        val probed = try {
            probeEndpointCombination(timeoutMs)
        } catch (t: Throwable) {
            Logx.w(TAG, "端点探测过程抛出异常（已忽略，改用默认组合）: ${t.javaClass.simpleName}: ${t.message}")
            false
        }

        if (probed) {
            Logx.i(TAG, "端点探测成功，使用实测确认的组合")
        } else {
            Logx.w(TAG, "端点探测未找到可用组合 —— 大概率不是端点问题，而是相机没在 PTP 模式。" +
                "将按默认组合继续，以便上层给出更有信息的错误。")
            // 探测过程会打乱 epCmdOut/epDataIn，必须恢复到描述符推断的组合，
            // 否则后续错误信息会指向错的端点，误导排查。
            restoreDefaultEndpoints()
        }

        Logx.i(TAG, "USB PTP ready: cmdOut=${epCmdOut?.address?.let { "0x%02X".format(it) }} " +
            "dataIn=${epDataIn?.address?.let { "0x%02X".format(it) }} " +
            "dataOut=${epDataOut?.address?.let { "0x%02X".format(it) }} " +
            "event=${epEvent?.address?.let { "0x%02X".format(it) }}")
    }

    /**
     * 把接口恢复到干净的一次性配置。
     *
     * 相机被系统 MTP 服务认领过之后，端点可能带着残留的 halt / data toggle 状态。
     * 这里显式 SET_INTERFACE(alt=0) 并要求内核复位数据翻转，再逐端点 clearHalt，
     * 让设备回到「刚枚举完」的状态 —— 这是解决"第一个传输就 -1"的标准做法。
     */
    private fun normalizeInterface(conn: UsbDeviceConnection, iface: UsbInterface) {
        // 1) 显式选择 alt setting 0（同时复位该接口下所有端点的数据翻转）
        try {
            val ok = conn.setInterface(iface)
            Logx.i(TAG, "setInterface(alt=0) -> $ok")
        } catch (t: Throwable) {
            Logx.w(TAG, "setInterface 异常: ${t.message}")
        }

        // 2) 逐个端点清 halt（命令 OUT 与数据 IN 都要）
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            clearHalt(conn, ep)
        }

        // 3) 给固件一点时间完成状态切换
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
        }
    }

    private fun pickInterface(dev: UsbDevice): UsbInterface? {
        // 优先 Still-Image(6)，其次任意含 bulk 端点的接口
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return itf
        }
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                if (itf.getEndpoint(e).type == UsbConstants.USB_ENDPOINT_XFER_BULK) return itf
            }
        }
        return null
    }

    private fun bindEndpoints(iface: UsbInterface, conn: UsbDeviceConnection) {
        val bulkIns = ArrayList<UsbEndpoint>()
        val bulkOuts = ArrayList<UsbEndpoint>()
        val intIns = ArrayList<UsbEndpoint>()

        for (i in 0 until iface.endpointCount) {
            val e = iface.getEndpoint(i)
            val dir = if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
            val kind = when (e.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                else -> "CTRL"
            }
            Logx.i(TAG, "  ep 0x%02X %s/%s maxPacket=%d interval=%d"
                .format(e.address, dir, kind, e.maxPacketSize, e.interval))
            when {
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN -> bulkIns.add(e)
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK -> bulkOuts.add(e)
                e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN -> intIns.add(e)
            }
        }
        bulkIns.sortBy { it.address and 0x7F }
        bulkOuts.sortBy { it.address and 0x7F }
        intIns.sortBy { it.address and 0x7F }

        if (bulkIns.isEmpty()) throw PtpIoException("设备没有 bulk IN 端点（不是标准 PTP 相机？）")
        if (bulkOuts.isEmpty()) throw PtpIoException("设备没有 bulk OUT 端点")

        // ------------------------------------------------------------------
        // 端点分配规则（Annex D / USB Still Image）：
        //
        //   · 命令 + 数据 可以共用一个 bulk OUT，也可以分成两个 bulk OUT；
        //   · 数据/响应固定一个 bulk IN；
        //   · 事件是 **Interrupt IN**（Teensy/USBHost_t36 实测的 PTP 相机是
        //     bulk OUT 0x04 / bulk IN 0x84 / INT IN 0x83）。
        //
        // 所以"数据端点是不是独立"**必须按实际端点数量推断**，不能一刀切：
        //   - 2 个以上 bulk OUT 且 2 个以上 bulk IN → 独立数据端点（第二个 OUT）
        //   - 否则 → 命令与数据共用第一个 OUT
        //
        // ⚠️ 之前踩的坑：只按"有 2 个 OUT 就用第 2 个"来判断，会在某些机身上
        //    选到「事件确认」端点（写它必然 -1）；反过来一刀切"永远共用"，
        //    又会让真正需要独立数据端点的机身失败。必须同时看 IN 的数量。
        // ------------------------------------------------------------------
        val independentDataEp = bulkOuts.size >= 2 && bulkIns.size >= 2

        epCmdOut = bulkOuts.firstOrNull()
        epDataOut = if (independentDataEp) bulkOuts[1] else bulkOuts.firstOrNull()
        allBulkOuts = bulkOuts.toList()
        allBulkIns = bulkIns.toList()

        epDataIn = overrideDataInAddr?.let { addr -> bulkIns.firstOrNull { it.address == addr } }
            ?: bulkIns[0]

        epEvent = overrideEventAddr?.let { addr ->
            intIns.firstOrNull { it.address == addr } ?: bulkIns.firstOrNull { it.address == addr }
        } ?: intIns.firstOrNull()
        ?: if (bulkIns.size > 1) bulkIns[1] else null

        Logx.i(TAG, "端点绑定: cmdOut=0x%02X dataOut=0x%02X%s dataIn=0x%02X event=%s".format(
            epCmdOut!!.address,
            epDataOut!!.address,
            if (independentDataEp) "(独立)" else "(与命令共用)",
            epDataIn!!.address,
            epEvent?.let { "0x%02X(%s)".format(it.address, if (it.type == UsbConstants.USB_ENDPOINT_XFER_INT) "INT" else "BULK") } ?: "无",
        ))
        Logx.i(TAG, "端点统计: bulkOut=${bulkOuts.size} bulkIn=${bulkIns.size} intIn=${intIns.size} " +
            "→ 数据端点%s".format(if (independentDataEp) "独立" else "共用"))

        // 清空可能残留的旧事务数据（上次会话被强杀时相机端可能还挂着数据）
        val junk = ByteArray(4096)
        var drained = 0
        while (drained < 16) {
            val n = try {
                conn.bulkTransfer(epDataIn, junk, junk.size, 20)
            } catch (t: Throwable) {
                -1
            }
            if (n <= 0) break
            drained++
        }
    }

    /**
     * 裸 USB 健康检查：**完全绕开 PTP 协议**，只验证「这块 USB 通路到底能不能收发字节」。
     *
     * 为什么需要它：`bulkTransfer` 失败统一返回 -1，把 EPIPE / EAGAIN / ETIMEDOUT
     * 三种完全不同的错误折叠成了一个数字。当错误时快时慢（11ms / 34ms / 10s），
     * 说明底层原因在变，继续在 PTP 逻辑里找已经找不到东西了。
     *
     * 这个检查只做三件事：
     *  1) 控制传输 GET_DESCRIPTOR(device) —— 验证控制管道（不需要接口，最基础）
     *  2) 控制传输 GET_STATUS(endpoint)   —— 验证端点存在且可查询
     *  3) 对每个 bulk 端点做一次「无意义写入 / 短读」—— 观察返回值与耗时
     *
     * 结果可以直接区分「USB 链路坏了」和「PTP 层写错了」。
     */
    fun usbHealthCheck(): String {
        val sb = StringBuilder()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            return "openDevice 失败：设备被占用或已拔出"
        }
        try {
            sb.appendLine("设备: " + describeDevice(device))

            // 1) 控制传输：读设备描述符前 18 字节
            val desc = ByteArray(18)
            val t0 = System.currentTimeMillis()
            val n1 = conn.controlTransfer(0x80, 0x06, 0x0100, 0, desc, desc.size, 2000)
            sb.appendLine("GET_DESCRIPTOR(device): $n1 字节, ${System.currentTimeMillis() - t0}ms" +
                if (n1 > 0) ", bLength=${desc[0].toInt() and 0xFF} class=${desc[4].toInt() and 0xFF}"
                else "  ← 控制管道就不通，USB 链路有问题")

            // 2) 接口 / 端点结构
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                sb.appendLine("iface ${itf.id}: class=${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol} eps=${itf.endpointCount}")
                for (j in 0 until itf.endpointCount) {
                    val e = itf.getEndpoint(j)
                    sb.appendLine("  ep 0x%02X dir=%s type=%d maxPacket=%d".format(
                        e.address,
                        if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                        e.type, e.maxPacketSize))
                }
            }

            // 3) 认领接口的两种方式都试
            val iface = pickInterface(device)
            if (iface != null) {
                usbInterface = iface
                val soft = conn.claimInterface(iface, false)
                sb.appendLine("claimInterface(force=false): $soft")
                var ok = soft
                if (!ok) {
                    val force = conn.claimInterface(iface, true)
                    sb.appendLine("claimInterface(force=true) : $force  ← 说明被系统驱动占用")
                    ok = force
                }
                if (ok) {
                    // 4) 每个 bulk 端点各做一次真实传输，观察耗时与返回值
                    for (j in 0 until iface.endpointCount) {
                        val e = iface.getEndpoint(j)
                        if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (e.direction == UsbConstants.USB_DIR_OUT) {
                            // 写一个 12 字节的空命令容器（不期望设备回应）
                            val probe = ByteArray(12)
                            probe[0] = 12
                            probe[4] = 1 // type = COMMAND
                            val ts = System.currentTimeMillis()
                            val n = conn.bulkTransfer(e, probe, probe.size, 1500)
                            sb.appendLine("bulk OUT 0x%02X 写12B: 返回 %d, %dms".format(
                                e.address, n, System.currentTimeMillis() - ts))
                        } else {
                            val buf = ByteArray(64)
                            val ts = System.currentTimeMillis()
                            val n = conn.bulkTransfer(e, buf, buf.size, 1500)
                            sb.appendLine("bulk IN  0x%02X 读64B: 返回 %d, %dms".format(
                                e.address, n, System.currentTimeMillis() - ts))
                        }
                    }
                    conn.releaseInterface(iface)
                }
                usbInterface = null
            }
            sb.appendLine()
            sb.append("判读：控制传输正常 + bulk 写入返回 12 = USB 链路健康，问题在 PTP 层；")
            sb.append("控制传输也失败或 bulk 全部 -1 = 线材/转接头/相机 USB 模式有问题。")
        } catch (t: Throwable) {
            sb.appendLine("异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                conn.close()
            } catch (_: Throwable) {
            }
        }
        return sb.toString()
    }

    /**
     * 清除端点 halt 状态。
     *
     * Android 的 [UsbDeviceConnection] **没有** libusb 那样的 `clearHalt()`，
     * 必须手工发一个标准控制传输：
     *   bmRequestType = 0x02 (Host→Device, Standard, Endpoint)
     *   bRequest      = 0x01 (CLEAR_FEATURE)
     *   wValue        = 0x0000 (ENDPOINT_HALT)
     *   wIndex        = 端点地址
     * 失败时返回 false，不影响主流程（只是少了恢复手段）。
     */
    private fun clearHalt(conn: UsbDeviceConnection, ep: UsbEndpoint): Boolean {
        return try {
            val r = conn.controlTransfer(
                0x02,       // requestType: HOST_TO_DEVICE | STANDARD | ENDPOINT
                0x01,       // CLEAR_FEATURE
                0x0000,     // ENDPOINT_HALT
                ep.address, // wIndex = endpoint address
                null, 0, 500
            )
            if (r >= 0) {
                Logx.i(TAG, "clearHalt 端点 0x%02X 成功".format(ep.address))
                true
            } else {
                Logx.w(TAG, "clearHalt 端点 0x%02X 返回 $r".format(ep.address))
                false
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "clearHalt 端点 0x%02X 异常: ${t.message}".format(ep.address))
            false
        }
    }

    /**
     * 端点写失败（-1）后尝试恢复。
     *
     * bulkTransfer 返回 -1 的常见原因：
     *  1. 端点被 halt（上一次传输被设备拒绝）→ 需要 clearHalt；
     *  2. 选错了端点（比如写了事件确认端点）→ 换端点重试；
     *  3. 设备超时没响应 / 相机不在 PTP 模式。
     *
     * 这里做 1 和 2，并把每一次尝试都记进日志，真机上一眼能看出是哪一类。
     */
    private fun sendRobust(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray, timeoutMs: Int): Int {
        if (data.isEmpty()) return 0
        var sent = conn.bulkTransfer(ep, data, data.size, timeoutMs)
        if (sent == data.size) return sent

        Logx.w(TAG, "bulkTransfer 端点 0x%02X 发送失败 (sent=%d size=%d) —— 尝试 clearHalt"
            .format(ep.address, sent, data.size))
        clearHalt(conn, ep)
        sent = conn.bulkTransfer(ep, data, data.size, timeoutMs)
        if (sent == data.size) {
            Logx.i(TAG, "clearHalt 后重发成功（端点原来处于 halt 状态）")
            return sent
        }

        // 换一个 bulk OUT 端点再试（应对"选错端点"的情况）
        val others = allBulkOuts.filter { it.address != ep.address }
        for (alt in others) {
            Logx.w(TAG, "改用备用 bulk OUT 端点 0x%02X 重试".format(alt.address))
            val s2 = conn.bulkTransfer(alt, data, data.size, timeoutMs)
            if (s2 == data.size) {
                Logx.i(TAG, "备用端点 0x%02X 可用，已切换（原端点 0x%02X 不可写）"
                    .format(alt.address, ep.address))
                epCmdOut = alt
                epDataOut = alt
                return s2
            }
        }
        return sent
    }

    /** 记录本次会话所有 bulk OUT 端点，供 [sendRobust] 换端点重试。 */
    private var allBulkOuts: List<UsbEndpoint> = emptyList()

    /** 记录所有 bulk IN 端点，供探测期遍历。 */
    private var allBulkIns: List<UsbEndpoint> = emptyList()

    /** 端点组合是否已经过实测确认（探测成功过）。 */
    @Volatile
    private var endpointsProbed = false

    /**
     * 按 USB 描述符推断的默认组合，探测失败时用于恢复。
     * Z6II 实测：OUT 只有 0x02 一个，命令与数据共用它；IN 0x81 收数据/响应，0x83 是 INT 事件。
     */
    private fun restoreDefaultEndpoints() {
        epCmdOut = allBulkOuts.firstOrNull()
        epDataIn = allBulkIns.firstOrNull()
        epDataOut = epCmdOut
        Logx.i(TAG, "恢复默认端点组合: cmdOut=0x%02X dataIn=0x%02X".format(
            epCmdOut?.address ?: -1, epDataIn?.address ?: -1))
    }

    /**
     * USB 传输失败时的排查提示。
     * bulkTransfer 返回 -1 时不带任何原因，只能靠这些经验判断。
     */
    private fun usbHint(): String = "\n逐项排查：\n" +
        "1) 相机 USB 模式必须是 **PTP/MTP**（不是「大容量存储」「充电」或「UVC」）；\n" +
        "   Z6II：设置 → USB → MTP/PTP。改完要拔插一次。\n" +
        "2) 线材：必须数据线 + OTG 转接头。劣质线只走供电，会表现为超时。\n" +
        "3) 若相机正被系统「照片」类 App / 其他相机 App 占用，先全部关掉。\n" +
        "4) 日志里看「端点绑定」那行，确认 cmdOut 不是 0x02（事件确认端点）。\n" +
        "5) 相机没插存储卡时部分机型会拒绝外部控制。"

    /**
     * 探测期：自动尝试所有端点组合，找出真正能通的那一组。
     *
     * 为什么需要这个：端点语义在不同机身/固件上不一致（命令与数据是否共用 OUT、
     * 事件是 INT IN 还是第二个 bulk IN），只靠翻规范判断在真机上并不可靠。
     *
     * 做法：用 **零参数的 OpenSession(0x1002)** 当探针 —— 它不产生数据段，
     * 只验证 [命令 OUT → 数据 IN] 这一条主通路，是最小的可判定单元。
     * 逐个组合试，第一个成功的立即锁定。锁定后此后的所有事务都用这组端点。
     */
    private fun probeEndpointCombination(timeoutMs: Int): Boolean {
        val conn = connection ?: return false
        val cmdCandidates = allBulkOuts
        val inCandidates = allBulkIns
        if (cmdCandidates.isEmpty() || inCandidates.isEmpty()) return false

        Logx.i(TAG, "开始端点探测：${cmdCandidates.size} 个 OUT × ${inCandidates.size} 个 IN")

        for (outEp in cmdCandidates) {
            for (inEp in inCandidates) {
                // 先清掉这对端点上的陈旧状态
                clearHalt(conn, outEp)
                clearHalt(conn, inEp)
                drainEndpoint(conn, inEp)

                epCmdOut = outEp
                epDataIn = inEp

                val tid = nextTid()
                val cmd = ByteWriter(16)
                    .u32(16)
                    .u16(Ptp.CONT_COMMAND)
                    .u16(Ptp.OC_OpenSession)
                    .u32(tid.toLong())
                    .u32(1L) // SessionID
                    .toByteArray()

                val tWrite0 = System.currentTimeMillis()
                val sent = conn.bulkTransfer(outEp, cmd, cmd.size, timeoutMs)
                val writeMs = System.currentTimeMillis() - tWrite0
                Logx.i(TAG, "  组合 OUT=0x%02X IN=0x%02X → 写入 %d/%d, %dms"
                    .format(outEp.address, inEp.address, sent, cmd.size, writeMs))

                // 读响应：用**轮询方式**反复尝试，而不是一次 bulkTransfer 读到底。
                // 原因：设备可能先返回 0 字节（短包/NULL 包）再补上数据，
                // 一次性读取会把"暂时没数据"误判成"永久失败"。
                val resp = pollForResponse(conn, inEp)
                if (resp == null) {
                    Logx.w(TAG, "  组合 OUT=0x%02X IN=0x%02X → 轮询超时，无响应容器"
                        .format(outEp.address, inEp.address))
                    continue
                }

                if (resp.type == Ptp.CONT_RESPONSE) {
                    // ResponseCode 在 Code 字段，不在 body（详见 handleContainerStream 的注释）
                    val rc = resp.code
                    Logx.i(TAG, "  ✓ 组合 OUT=0x%02X IN=0x%02X 可用，响应=%s(0x%04X)"
                        .format(outEp.address, inEp.address, Ptp.responseName(rc), rc))
                    epCmdOut = outEp
                    epDataIn = inEp
                    // 数据输出端点：两个 OUT 时优先用另一个（独立数据端点），否则共用
                    epDataOut = if (cmdCandidates.size >= 2 && outEp.address == cmdCandidates[0].address) {
                        cmdCandidates[1]
                    } else {
                        outEp
                    }
                    endpointsProbed = true
                    Logx.i(TAG, "锁定端点: cmdOut=0x%02X dataOut=0x%02X dataIn=0x%02X"
                        .format(outEp.address, epDataOut!!.address, inEp.address))
                    return true
                } else {
                    Logx.w(TAG, "  组合 OUT=0x%02X IN=0x%02X → 收到非响应容器 type=${resp.type}"
                        .format(outEp.address, inEp.address))
                }
            }
        }
        return false
    }

    /**
     * 轮询读取一个响应容器。
     *
     * 设备回复数据可能不是一次到位：bulk IN 可能先返回 0 字节 / 短包（NULL packet），
     * 数据下一次才到。一次性 bulkTransfer 会把这种情况误判为失败。
     *
     * 这里做真正的"攒够再解析"：
     *  1. 反复读，直到攒满 12 字节头；
     *  2. 解析出 Length，再继续读直到攒满整个容器；
     *  3. 全程有总时长上限，超时才放弃。
     * 这样既能容忍分片，也不会无限等待。
     */
    private fun pollForResponse(conn: UsbDeviceConnection, ep: UsbEndpoint): RawContainer? {
        val deadline = System.currentTimeMillis() + 3000
        val head = ByteArray(12)
        var headGot = 0

        // 阶段 1：攒满 12 字节容器头
        while (headGot < 12 && System.currentTimeMillis() < deadline) {
            val n = try {
                conn.bulkTransfer(ep, head, headGot, 12 - headGot, 300)
            } catch (t: Throwable) {
                -1
            }
            if (n > 0) {
                headGot += n
            } else if (n < 0) {
                // -1：可能是暂时没数据，也可能是端点有问题。清 halt 后继续试，
                // 直到超时为止（300ms 一次，总共约 3 秒）。
                clearHalt(conn, ep)
            }
        }
        if (headGot < 12) {
            Logx.d(TAG, "  轮询失败：只收到 $headGot/12 字节容器头")
            return null
        }

        val r = ByteReader(head)
        val len = r.u32().toInt()
        val type = r.u16()
        val code = r.u16()
        val tid = r.u32().toInt()
        if (len < 12 || len > MAX_CONTAINER) {
            Logx.w(TAG, "  轮询得到非法容器长度 $len, head=${head.hex(16)}")
            return null
        }

        // 阶段 2：攒满容器体
        val body = ByteArray(len - 12)
        var off = 0
        while (off < body.size && System.currentTimeMillis() < deadline) {
            val n = try {
                conn.bulkTransfer(ep, body, off, body.size - off, 300)
            } catch (t: Throwable) {
                -1
            }
            if (n > 0) off += n
            else if (n < 0) clearHalt(conn, ep)
        }
        if (off < body.size) {
            Logx.d(TAG, "  轮询失败：容器体只收到 $off/${body.size} 字节")
            return null
        }
        Logx.d(TAG, "  轮询成功：type=$type code=0x%04X tid=$tid len=$len".format(code))
        return RawContainer(type, code, tid, body)
    }

    private fun drainEndpoint(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val junk = ByteArray(4096)
        var n = 0
        while (n < 8) {
            val r = try {
                conn.bulkTransfer(ep, junk, junk.size, 20)
            } catch (t: Throwable) {
                -1
            }
            if (r <= 0) break
            n++
        }
    }

    /** 用指定端点读一个容器（探测期专用，不依赖当前字段）。 */
    private fun readContainerWith(ep: UsbEndpoint, timeoutMs: Int): RawContainer? {
        val conn = connection ?: return null
        val header = ByteArray(12)
        val got = conn.bulkTransfer(ep, header, header.size, timeoutMs)
        if (got < 12) {
            if (got > 0) Logx.w(TAG, "  容器头只读到 $got 字节: ${header.copyOf(got).hex(16)}")
            return null
        }
        val r = ByteReader(header)
        val len = r.u32().toInt()
        val type = r.u16()
        val code = r.u16()
        val tid = r.u32().toInt()
        if (len < 12 || len > MAX_CONTAINER) {
            Logx.w(TAG, "  非法容器长度 $len，头: ${header.hex(16)}")
            return null
        }
        val body = ByteArray(len - 12)
        var off = 0
        while (off < body.size) {
            val n = conn.bulkTransfer(ep, body, off, body.size - off, timeoutMs)
            if (n <= 0) return null
            off += n
        }
        return RawContainer(type, code, tid, body)
    }

    override fun request(
        opcode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        timeoutMs: Int,
    ): PtpResponse {
        val conn = connection ?: throw PtpIoException("USB 连接已关闭")
        val cmdEp = epCmdOut ?: throw PtpIoException("没有命令端点")
        val tid = nextTid()

        // --- 命令容器 ---
        val cw = ByteWriter(12 + params.size * 4)
            .u32((12 + params.size * 4).toLong())
            .u16(Ptp.CONT_COMMAND)
            .u16(opcode)
            .u32(tid.toLong())
        for (p in params) cw.u32(p.toLong() and 0xFFFF_FFFFL)
        val cmd = cw.toByteArray()

        Logx.d(TAG, "-> op=${Ptp.opcodeName(opcode)}(0x%04X) tid=$tid params=%s dataOut=%dB"
            .format(opcode, params.joinToString(","), dataOut?.size ?: 0))

        val sent = sendRobust(conn, cmdEp, cmd, timeoutMs)
        if (sent != cmd.size) {
            // 写入"失败"不代表命令没被处理。宁康/很多实现里 bulk OUT 写不进是常见现象
            // （尤其设备数据翻转不同步时），但命令可能已经进了设备。
            // 这里不立刻抛异常，而是**等一小会儿看相机是否仍然回应**：
            //  - 有响应 → 说明命令其实生效了，继续按正常流程解析
            //  - 无响应 → 才是真的失败
            Logx.w(TAG, "命令容器写入返回 $sent/${cmd.size}，转为轮询响应以确认相机是否已处理")
            val maybe = if (endpointsProbed) {
                pollForResponse(conn, cmdDataIn())
            } else {
                null
            }
            if (maybe == null) {
                throw PtpIoException(
                    "发送命令容器失败 ($sent/${cmd.size})，端点 0x%02X".format(cmdEp.address) +
                        "，且相机无响应。" + usbHint()
                )
            }
            Logx.i(TAG, "相机有响应（说明命令实际已被处理，仅写入状态异常）")
            return handleContainerStream(maybe)
        }

        // --- 可选 DataOut 容器 ---
        if (dataOut != null) {
            val outEp = epDataOut ?: cmdEp
            val dw = ByteWriter(12 + dataOut.size)
                .u32((12 + dataOut.size).toLong())
                .u16(Ptp.CONT_DATA)
                .u16(opcode)
                .u32(tid.toLong())
                .raw(dataOut)
            val dbytes = dw.toByteArray()
            val dSent = sendRobust(conn, outEp, dbytes, timeoutMs)
            if (dSent != dbytes.size) {
                throw PtpIoException(
                    "发送数据容器失败 ($dSent/${dbytes.size})，端点 0x%02X".format(outEp.address) + usbHint()
                )
            }
        }

        // --- 读数据段 / 响应段 ---
        return handleContainerStream(readContainer())
    }

    /** 命令写入失败时用于读响应的 IN 端点（保持在探测/绑定阶段确定的那一个）。 */
    private fun cmdDataIn(): UsbEndpoint =
        epDataIn ?: throw PtpIoException("没有数据 IN 端点")

    /**
     * 从给定的第一个容器开始，持续读到 RESPONSE 为止。
     * Data 段累积，Event 段分发给上层，其余类型记日志后忽略。
     */
    private fun handleContainerStream(first: RawContainer): PtpResponse {
        val acc = java.io.ByteArrayOutputStream()
        var cur: RawContainer? = first
        while (true) {
            val x = cur ?: readContainer()
            cur = null
            when (x.type) {
                Ptp.CONT_DATA -> {
                    acc.write(x.payload)
                    Logx.d(TAG, "  <- DATA ${x.payload.size}B (累计 ${acc.size()})")
                }
                Ptp.CONT_RESPONSE -> {
                    // ⚠️ 关键：PTP **响应容器**的载荷布局与其他容器不同。
                    //
                    //   命令/数据/事件容器：Length | Type | Code | TID | Payload
                    //   响应容器：        Length | Type(=3) | ResponseCode | TID | ResponseParams
                    //
                    // 也就是说 ResponseCode 放在 **Code** 字段（头部第 7~8 字节），
                    // 容器体里直接就是响应参数。当 Length == 12（无参数）时 body 是空的。
                    //
                    // 之前的实现一律从 body 读 ResponseCode，于是在无参数响应上
                    // 抛 "越界读取: 需要 2 字节, 偏移 0, 总长 0" —— 这正是
                    // 0x201E(SessionAlreadyOpen) 那种 12 字节响应触发的崩溃。
                    val rc = x.code
                    val rtid = x.tid
                    val rp = ArrayList<Long>()
                    if (x.payload.isNotEmpty()) {
                        val r = ByteReader(x.payload)
                        while (r.remaining >= 4) rp.add(r.u32())
                    }
                    val data = if (acc.size() > 0) acc.toByteArray() else null
                    Logx.d(TAG, "<- resp=${Ptp.responseName(rc)}(0x%04X) tid=$rtid data=%dB"
                        .format(rc, data?.size ?: 0))
                    return PtpResponse(rc, rp, data)
                }
                Ptp.CONT_EVENT -> dispatchEvent(parseEvent(x))
                else -> Logx.w(TAG, "未知容器类型 ${x.type}（若反复出现说明数据/事件流错位）")
            }
        }
    }

    private data class RawContainer(val type: Int, val code: Int, val tid: Int, val payload: ByteArray)

    /**
     * 读一个容器（命令通道的响应段/数据段）。
     *
     * ⚠️ 必须"攒够再解析"：USB bulk IN 不保证一次返回请求的字节数，
     * 常见情形是先回 0 字节或只回容器头的一部分（短包），剩下的下次才到。
     * 之前这里是"一次 bulkTransfer 读 12 字节"，一旦拿到 8 字节就当成完整头解析，
     * 会导致 Length/Type 读错 → 整条数据通路随机失败（表现为"连上但读不到属性表"）。
     *
     * 现在改为：反复读直到攒满 12 字节头 → 解析 Length → 继续攒满容器体。
     */
    private fun readContainer(): RawContainer {
        val conn = connection ?: throw PtpIoException("USB 连接已关闭")
        val ep = epDataIn ?: throw PtpIoException("没有数据 IN 端点")

        val header = ByteArray(12)
        var headGot = 0
        var firstAttempt = true
        while (headGot < 12) {
            val n = try {
                conn.bulkTransfer(ep, header, headGot, 12 - headGot, timeoutMs)
            } catch (t: Throwable) {
                -1
            }
            if (n > 0) {
                headGot += n
                firstAttempt = false
                continue
            }
            // 没读到数据
            if (firstAttempt) {
                clearHalt(conn, ep)
                firstAttempt = false
                continue
            }
            if (headGot > 0) {
                Logx.w(TAG, "容器头只收到 $headGot/12 字节: ${header.copyOf(headGot).hex(12)}")
            }
            throw PtpIoException(
                "读取容器头失败（已收 $headGot/12 字节），端点 0x%02X".format(ep.address) +
                    " —— 相机没有回响应。" + usbHint()
            )
        }

        val r = ByteReader(header)
        val len = r.u32().toInt()
        val type = r.u16()
        val code = r.u16()
        val tid = r.u32().toInt()
        if (len < 12 || len > MAX_CONTAINER) {
            throw PtpIoException(
                "非法容器长度 $len（type=$type code=0x%04X）".format(code) +
                    " —— 数据/响应流错位。header=${header.hex(24)}"
            )
        }

        val body = ByteArray(len - 12)
        var off = 0
        while (off < body.size) {
            val n = try {
                conn.bulkTransfer(ep, body, off, body.size - off, timeoutMs)
            } catch (t: Throwable) {
                -1
            }
            if (n > 0) {
                off += n
            } else {
                // 容器体中途断了：再试一次（清 halt），仍失败则报错
                clearHalt(conn, ep)
                val n2 = try {
                    conn.bulkTransfer(ep, body, off, body.size - off, timeoutMs)
                } catch (t: Throwable) {
                    -1
                }
                if (n2 > 0) off += n2
                else throw PtpIoException("读取容器体失败 (已读 $off/${body.size})")
            }
        }
        Logx.d(TAG, "容器: type=$type code=0x%04X tid=$tid len=$len".format(code))
        return RawContainer(type, code, tid, body)
    }

    override fun beginEventPump(onEvent: (PtpEvent) -> Unit, onError: (Throwable) -> Unit) {
        eventHandler = onEvent
        errorHandler = onError
        val ep = epEvent
        if (ep == null) {
            Logx.w(TAG, "该设备没有独立的事件端点，本次会话只能靠主动轮询/音频触发")
            return
        }
        if (!pumpRunning.compareAndSet(false, true)) return
        val t = Thread({
            val conn = connection
            val buf = ByteArray(1024)
            while (pumpRunning.get() && conn != null) {
                val n = try {
                    conn.bulkTransfer(ep, buf, buf.size, 200)
                } catch (t: Throwable) {
                    -1
                }
                if (n >= 12) {
                    val r = ByteReader(buf.copyOfRange(0, n))
                    val len = r.u32().toInt()
                    val type = r.u16()
                    val code = r.u16()
                    val tid = r.u32().toInt()
                    val params = ArrayList<Long>()
                    while (r.remaining >= 4) params.add(r.u32())
                    if (type == Ptp.CONT_EVENT && len >= 12) {
                        dispatchEvent(PtpEvent(code, tid, params))
                    }
                }
                // n<0 只是超时，继续转
            }
            pumpRunning.set(false)
        }, "ptp-usb-events")
        t.isDaemon = true
        pumpThread = t
        t.start()
    }

    override fun endEventPump() {
        pumpRunning.set(false)
        pumpThread = null
    }

    override val pushEventsActive: Boolean get() = epEvent != null

    /** 端点探测结果摘要（诊断用）。 */
    fun endpointSummary(): String = buildString {
        appendLine("cmdOut=${epCmdOut?.address?.let { "0x%02X".format(it) } ?: "-"} " +
            "dataOut=${epDataOut?.address?.let { "0x%02X".format(it) } ?: "-"} " +
            "dataIn=${epDataIn?.address?.let { "0x%02X".format(it) } ?: "-"} " +
            "event=${epEvent?.address?.let { "0x%02X".format(it) } ?: "-"}")
        appendLine("探测: ${if (endpointsProbed) "已实测确认" else "未确认（按默认推断）"}")
        append("全部 bulk OUT: " + allBulkOuts.joinToString { "0x%02X".format(it.address) })
        appendLine()
        append("全部 bulk IN : " + allBulkIns.joinToString { "0x%02X".format(it.address) })
    }

    private fun dispatchEvent(e: PtpEvent) {
        try {
            eventHandler?.invoke(e)
        } catch (t: Throwable) {
            Logx.e(TAG, "事件处理异常", t)
        }
    }

    private fun parseEvent(c: RawContainer): PtpEvent {
        // 事件容器与命令/数据容器同构：Code 字段是事件码，body 里是事件参数。
        val params = ArrayList<Long>()
        if (c.payload.isNotEmpty()) {
            val r = ByteReader(c.payload)
            while (r.remaining >= 4) params.add(r.u32())
        }
        return PtpEvent(c.code, c.tid, params)
    }

    private fun nextTid(): Int {
        val v = transactionId
        transactionId = if (transactionId >= 0xFFFF_FFFE) 1 else transactionId + 1
        return v
    }

    override fun close() {
        endEventPump()
        try {
            request(Ptp.OC_CloseSession, timeoutMs = 1500)
        } catch (_: Throwable) {
        }
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Throwable) {
        }
        try {
            connection?.close()
        } catch (_: Throwable) {
        }
        connection = null
        Logx.i(TAG, "closed")
    }

    companion object {
        private const val TAG = "PtpUsb"
        private const val MAX_CONTAINER = 64 * 1024 * 1024

        private const val ACTION_USB_PERMISSION = "com.lumenlink.USB_PERMISSION"

        /** 枚举所有看起来像 PTP 相机（Still-Image 类）的 USB 设备。 */
        fun findPtpDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { dev ->
                (0 until dev.interfaceCount).any { i ->
                    val itf = dev.getInterface(i)
                    itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                        (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.endpointCount >= 3)
                }
            }

        /**
         * 申请 USB 使用权限。返回的 PendingIntent 结果会以
         * action=[ACTION_USB_PERMISSION] 的广播发回来（由调用方注册 receiver）。
         *
         * 注意 Android 12+ 必须显式给 PendingIntent 填 FLAG_MUTABLE，
         * 系统 USB 服务要往里写入 EXTRA_PERMISSION_GRANTED。
         */
        fun buildPermissionIntent(context: Context, device: UsbDevice): PendingIntent {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val requestCode = device.deviceId and 0xFFFF
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }

        fun permissionAction(): String = ACTION_USB_PERMISSION

        fun describeDevice(dev: UsbDevice): String =
            "vid=0x%04X pid=0x%04X %s/%s ifaces=%d".format(
                dev.vendorId, dev.productId,
                dev.manufacturerName ?: "?", dev.productName ?: "?", dev.interfaceCount
            )
    }
}
