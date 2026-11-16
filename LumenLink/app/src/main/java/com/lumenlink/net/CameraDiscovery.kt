package com.lumenlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.lumenlink.core.Logx
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 相机热点发现。
 *
 * 用户连上相机 Wi-Fi 后，**不应该还要手输 IP**。这里做三件事：
 *
 *  1. 找出承载 Wi-Fi 的 [Network] 对象（必须拿它去绑 socket！见下）；
 *  2. 列出本机所有 IPv4 地址与所属网卡（区分是否 Wi-Fi）；
 *  3. 从 WIFI 网卡推断候选相机地址（网关 → 同网段 .1 → 固定兜底），
 *     交给调用方逐个做**完整 PTP/IP 握手**。
 *
 * ## 为什么一定要拿 Network 对象
 *
 * 相机热点**没有外网**。Android 在多网络并存时会把 socket 的默认出口指向
 * “有外网”的那张网卡（蜂窝/家用 Wi-Fi）。直接 `Socket()` 然后 connect
 * 相机地址，会出现三种诡异现象（实测都发生过）：
 *
 *  - `192.168.1.1` → connection refused（其实落到别的网卡上被对端 RST）
 *  - `192.168.1.1` → 10 秒超时
 *  - 第一轮失败后第二轮直接 `ECONNABORTED / ENETUNREACH`
 *
 * 所以连接时必须 `network.bindSocket(socket)`（见 PtpIpTransport）。
 */
object CameraDiscovery {

    private const val TAG = "Discovery"
    const val PTP_PORT = 15740

    /**
     * 候选端口。尼康机身不同固件/型号用的端口并不统一：
     *  - 15740：PTP/IP 标准端口（老款 WMU / 部分 Z）
     *  - 15741 / 15742：部分 Z 系列固件
     *  - 45204：SnapBridge 内部用过的端口
     */
    val CANDIDATE_PORTS = intArrayOf(15740, 15741, 15742, 45204)

    data class LocalIface(
        val name: String,
        val ipv4: String,
        val isWifi: Boolean,
    )

    /** 本机 Wi-Fi 网卡上的一门可用网络。 */
    data class WifiRoute(
        val network: Network,
        val localIp: String,
        val gateway: String?,
        val dnsHint: String?,
        val ssidHint: String,
    ) {
        fun describe(): String =
            "local=$localIp gw=${gateway ?: "-"} dns=${dnsHint ?: "-"} ssid=$ssidHint"
    }

    /**
     * 列出**所有** Wi-Fi 网络（不只是默认网络）。
     *
     * 相机热点很可能不是默认网络（尤其开了移动数据或还连着家里 Wi-Fi 时），
     * 所以必须遍历 allNetworks 而不是只看 activeNetwork。
     */
    fun wifiRoutes(context: Context): List<WifiRoute> {
        val out = ArrayList<WifiRoute>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return out
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp: LinkProperties = cm.getLinkProperties(n) ?: continue
                val local = lp.linkAddresses
                    .mapNotNull { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?: continue
                val gw = lp.routes
                    ?.mapNotNull { it.gateway }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                val dns = lp.dnsServers.filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }?.hostAddress
                out.add(
                    WifiRoute(
                        network = n,
                        localIp = local,
                        gateway = gw,
                        dnsHint = dns,
                        ssidHint = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "有外网" else "无外网",
                    )
                )
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "枚举 Wi-Fi 网络失败: ${t.message}")
        }
        return out
    }

    /** 列出本机 IPv4 地址。 */
    fun localAddresses(): List<LocalIface> {
        val out = ArrayList<LocalIface>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(
                            LocalIface(
                                name = nif.name,
                                ipv4 = addr.hostAddress ?: "",
                                isWifi = nif.name.startsWith("wlan") || nif.name.startsWith("ap"),
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "枚举网卡失败: ${t.message}")
        }
        return out
    }

    private fun validIp(s: String?): Boolean =
        s != null && s.isNotBlank() && s != "0.0.0.0" && s != "::" && s != "::0"

    private fun subnetBase(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    /**
     * 判断一个 Wi-Fi 网络"像不像相机热点"。
     *
     * 依据：没有外网能力，或网关/网段落在尼康 AP 的常见区间。
     * 排序用它，避免先花 10 秒超时去试家里路由器。
     */
    private fun looksLikeCameraAp(context: Context, r: WifiRoute): Int {
        var score = 0
        if (r.ssidHint == "无外网") score += 4
        val gw = r.gateway
        if (gw != null && (gw.startsWith("192.168.1.") || gw.startsWith("192.168.0.") || gw.startsWith("10.0.0."))) {
            score += 2
        }
        if (r.localIp.startsWith("192.168.1.") || r.localIp.startsWith("192.168.0.")) score += 1
        // 本机是 .2/.3 这类"客户端地址"，大概率对端是 .1 的相机
        if (validIp(r.localIp) && !r.localIp.endsWith(".1")) score += 1
        return score
    }

    /**
     * 从一个探针地址推导同一子网内最可能是相机的地址。
     *
     * 用途：有些手机上 `LinkProperties.routes` 的网关报成 `0.0.0.0`（实测小米/Android 16），
     * 只有 DNS 或本机 IP 可用。尼康机身当 AP 时，自己固定占 **.1**，
     * 所以拿到任意同网段地址就能推出 `x.y.z.1`。
     */
    fun guessHostFromProbe(probe: String): String? {
        val base = subnetBase(probe) ?: return null
        return "$base.1"
    }

    /**
     * 生成候选列表：**优先用 Wi-Fi 网络的网关**，不掺无意义的 0.0.0.0。
     *
     * 返回顺序即为尝试顺序。第一项一定是当前 Wi-Fi 网关上最可能的相机地址。
     */
    fun candidateHosts(context: Context): List<String> {
        val set = LinkedHashSet<String>()

        val routes = wifiRoutes(context)
        routes.forEach { Logx.i(TAG, "Wi-Fi 网络: ${it.describe()}") }

        // 本机 Wi-Fi 实际所在的网段前缀集合。
        // ⚠️ 关键：只保留**与本机同网段**的候选地址。
        // 跨网段的地址（例如本机 192.168.2.4 却去试 192.168.1.1）
        // 会立刻被本地协议栈 RST，白白浪费十几秒还产生误导性日志。
        val localBases = LinkedHashSet<String>()
        for (r in routes) subnetBase(r.localIp)?.let { localBases.add(it) }
        for (i in localAddresses()) {
            if (i.isWifi) subnetBase(i.ipv4)?.let { localBases.add(it) }
        }
        Logx.i(TAG, "本机 Wi-Fi 网段: ${localBases.joinToString { "$it.x" }}")

        val ordered = routes.sortedByDescending { looksLikeCameraAp(context, it) }
        for (r in ordered) {
            if (validIp(r.gateway)) set.add(r.gateway!!)
            // 网关拿不到（报 0.0.0.0）时从 DNS / 本机 IP 推同网段 .1
            if (validIp(r.dnsHint)) {
                set.add(r.dnsHint!!)
                guessHostFromProbe(r.dnsHint!!)?.let { set.add(it) }
            }
            guessHostFromProbe(r.localIp)?.let { set.add(it) }
        }

        // 网卡兜底（拿不到 LinkProperties 时）
        for (i in localAddresses()) {
            if (!i.isWifi) continue
            subnetBase(i.ipv4)?.let { set.add("$it.1") }
        }

        // 过滤：必须与某张 Wi-Fi 网卡同网段
        val sameSubnet = set.filter { h ->
            val base = subnetBase(h)
            base != null && localBases.contains(base)
        }

        // 如果一张 Wi-Fi 都没有（拿不到任何网段信息），才退回固定候选
        if (sameSubnet.isEmpty()) {
            Logx.w(TAG, "无法确定本机网段，退回固定候选地址")
            return listOf("192.168.1.1", "192.168.0.1", "10.0.0.1")
        }

        val dropped = set - sameSubnet.toSet()
        if (dropped.isNotEmpty()) {
            Logx.i(TAG, "已剔除跨网段候选（必然失败）: ${dropped.joinToString()}")
        }
        return sameSubnet
    }

    /**
     * 判断当前 Wi-Fi 是否**像相机热点**，不像就返回提示文本。
     *
     * 依据：相机热点没有外网（`NET_CAPABILITY_INTERNET` 缺失），
     * 且尼康 AP 的网段基本是 192.168.1.x / 192.168.0.x。
     * 家里路由器两个条件都不满足。
     *
     * 返回 null 表示"看起来像相机热点，可以继续"。
     */
    fun notCameraHotspotHint(context: Context): String? {
        val routes = wifiRoutes(context)
        if (routes.isEmpty()) return "当前没有 Wi-Fi 连接。请先连上相机热点。"
        val best = routes.maxByOrNull { looksLikeCameraAp(context, it) } ?: return null
        val hasInternet = best.ssidHint == "有外网"
        val base = subnetBase(best.localIp)
        val knownApSubnet = base == "192.168.1" ||
            base == "192.168.0" ||
            base == "10.0.0" ||
            base == "192.168.4"
        if (!hasInternet || knownApSubnet) return null
        return buildString {
            appendLine("当前 Wi-Fi 看起来**不是相机热点**：")
            appendLine("  本机地址 ${best.localIp}  网段 ${base ?: "?"}.x")
            appendLine("  外网能力：${best.ssidHint}")
            appendLine()
            appendLine("相机热点应该是：")
            appendLine("  · 网段 192.168.1.x（相机固定占 .1）")
            appendLine("  · 显示「无外网」")
            appendLine()
            appendLine("现在很可能连的是家里路由器。请切换到相机热点后再试。")
        }
    }

    /**
     * 找到某个候选地址应该**从哪个网络出去**。
     *
     * 这是本次 Wi-Fi 失败的核心修复：socket 必须绑定到正确的 Network，
     * 否则流量会走蜂窝/家用 Wi-Fi，必然连不上相机。
     */
    fun networkFor(host: List<String>, routes: List<WifiRoute>): WifiRoute? {
        // 1) 网段完全匹配（最可靠）
        for (r in routes) {
            val base = subnetBase(r.localIp) ?: continue
            if (host.any { it.startsWith("$base.") }) return r
        }
        // 2) 退化为最像相机热点的那张 Wi-Fi 网卡
        return routes.maxByOrNull { r ->
            var s = 0
            if (r.ssidHint == "无外网") s += 4
            if (validIp(r.gateway)) s += 1
            s
        }
    }

    /** 逐个候选地址尝试**完整握手**，返回第一个成功的地址。 */
    fun tryEachHost(
        hosts: List<String>,
        onProgress: (String) -> Unit = {},
        attempt: (String) -> Boolean,
    ): String? {
        for (h in hosts) {
            onProgress("尝试 $h …")
            val ok = try {
                attempt(h)
            } catch (t: Throwable) {
                Logx.w(TAG, "$h 握手失败: ${t.message}")
                false
            }
            if (ok) {
                Logx.i(TAG, "✓ 使用 $h")
                return h
            }
        }
        return null
    }

    /**
     * 只做一次 TCP connect 判断端口是否开放（**不建立 PTP 会话**）。
     *
     * 注意语义：连上即断开。对相机 15740 端口这属于协议违规，
     * 所以只在「连通性诊断」里用，且每个地址最多一次。
     * 它的价值是区分两类完全不同的故障：
     *  - 连接被系统拦下（refused/timeout 且本机根本没发包）→ 权限/网络绑定问题
     *  - 连上了 → 相机在监听，问题在 PTP 层
     */
    fun tcpReachable(host: String, network: Network?, timeoutMs: Int = 2500): String {
        var sock: java.net.Socket? = null
        return try {
            val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
            sock = s
            network?.bindSocket(s)
            s.connect(java.net.InetSocketAddress(host, PTP_PORT), timeoutMs)
            "✓ 端口 $PTP_PORT 开放"
        } catch (t: Throwable) {
            "✗ ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            try {
                sock?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 全端口快扫（只用于诊断）。
     *
     * 目的：一次性看清相机到底开了哪些 TCP 端口。
     * 如果连 80 / 443 / 5000 这类常见服务端口都不存在，
     * 说明相机根本没启动任何服务 —— 只是把 Wi-Fi 被关联上而已。
     *
     * 只扫常见的几十个端口，每个 300ms，总体几秒内完成。
     * 对每一个开放端口会**立刻关闭**，不建 PTP 会话。
     */
    val DIAG_PORTS = intArrayOf(
        80, 443, 554, 8080, 8443,          // HTTP / RTSP
        15740, 15741, 15742, 15743,        // PTP/IP 家族
        45204, 45205,                       // 尼康 SnapBridge 曾用
        5353,                               // mDNS(TCP 少见但试)
        8008, 8009,                         // 投屏/其他
        21, 22, 23,                         // 管理协议
        25, 110, 143,                       // 邮件（排除误判）
        3306, 5432,                         // 数据库（排除误判）
        5000, 5001,                         // UPnP/常见 Web
        9000, 9090,
    )

    fun scanPortRange(
        host: String,
        network: Network?,
        ports: IntArray = DIAG_PORTS,
        timeoutMs: Int = 300,
    ): List<PortHit> {
        val out = ArrayList<PortHit>()
        for (p in ports) {
            var sock: java.net.Socket? = null
            val hit = try {
                val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
                sock = s
                network?.bindSocket(s)
                s.connect(java.net.InetSocketAddress(host, p), timeoutMs)
                PortHit(host, p, "开放", true)
            } catch (t: Throwable) {
                PortHit(host, p, t.javaClass.simpleName, false)
            } finally {
                try {
                    sock?.close()
                } catch (_: Throwable) {
                }
            }
            if (hit.open) Logx.i(TAG, "★ 端口 $host:${hit.port} 开放")
            out.add(hit)
        }
        return out
    }

    /**
     * mDNS 查询 `_ptp._tcp.local` 并**解析**返回内容。
     *
     * 为什么这是决定性的证据：
     * 尼康 Z 系列在 WiFi 模式下会广播 `_ptp._tcp` 服务，端口 15740，
     * TXT 记录里的 `apps` 字段直接反映配对状态：
     *   apps=PAIR  → 当前配置文件未配对本机，需要配对
     *   apps=$DSC  → 已配对
     *
     * 如果**查不到这个服务**，说明相机根本没启动 PTP 服务
     * （不是端口号猜错，也不是权限问题）。
     *
     * @return 人类可读的报告；包含是否发现服务、端口、TXT 记录、目标 IP
     */
    fun mdnsQueryPtp(network: Network?, timeoutMs: Int = 3000): String {
        val sb = StringBuilder()
        var sock: java.net.DatagramSocket? = null
        try {
            sock = java.net.DatagramSocket().apply {
                try {
                    network?.bindSocket(this)
                } catch (t: Throwable) {
                    Logx.w(TAG, "bindSocket 失败: ${t.message}")
                }
                broadcast = true
                soTimeout = 1000
                reuseAddress = true
            }

            val query = buildMdnsQuery()
            val targets = ArrayList<java.net.InetSocketAddress>()
            try {
                targets.add(
                    java.net.InetSocketAddress(java.net.InetAddress.getByName("224.0.0.251"), 5353)
                )
            } catch (_: Throwable) {
            }
            try {
                targets.add(
                    java.net.InetSocketAddress(java.net.InetAddress.getByName("255.255.255.255"), 5353)
                )
            } catch (_: Throwable) {
            }

            for (t in targets) {
                try {
                    sock.send(java.net.DatagramPacket(query, query.size, t))
                    Logx.i(TAG, "mDNS 已发送到 $t")
                } catch (e: Throwable) {
                    Logx.w(TAG, "mDNS 发送失败 $t: ${e.message}")
                }
            }

            sb.appendLine("=== mDNS 查询 _ptp._tcp.local ===")
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            var got = 0
            val seenIps = LinkedHashSet<String>()
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    got++
                    val from = pkt.address?.hostAddress ?: "?"
                    seenIps.add(from)
                    val parsed = parseMdnsResponse(pkt.data, pkt.length)
                    sb.appendLine("--- 来自 $from（${pkt.length} 字节）---")
                    if (parsed.isBlank()) {
                        sb.appendLine("  （未解析出 A/PTR/SRV/TXT 记录）")
                    } else {
                        sb.appendLine(parsed.trimEnd())
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 继续等到总截止时间
                } catch (e: Throwable) {
                    Logx.w(TAG, "mDNS 接收异常: ${e.message}")
                    break
                }
            }

            sb.appendLine()
            sb.appendLine("收到 $got 个 mDNS 应答包")
            if (got == 0) {
                sb.appendLine()
                sb.appendLine("【结论】没有任何设备应答 mDNS。")
                sb.appendLine("→ 相机没有广播 mDNS 服务（部分固件可能不广播，不能单独据此判定）")
                sb.appendLine("→ 请改用「★ 全端口快扫」确认是否有任何 TCP 端口开放。")
            } else {
                sb.appendLine("应答来源: ${seenIps.joinToString()}")
            }
        } catch (t: Throwable) {
            sb.appendLine("mDNS 查询失败: ${t.message}")
            Logx.e(TAG, "mDNS 查询失败", t)
        } finally {
            try {
                sock?.close()
            } catch (_: Throwable) {
            }
        }
        return sb.toString()
    }

    /** 构造 `_ptp._tcp.local` 的 PTR 查询包。 */
    private fun buildMdnsQuery(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Header: ID=0, Flags=0, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        // QNAME: _ptp._tcp.local
        for (label in listOf("_ptp", "_tcp", "local")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        // QTYPE=PTR(12), QCLASS=IN(1)
        out.write(byteArrayOf(0, 12, 0, 1))
        return out.toByteArray()
    }

    /**
     * 极简 mDNS 应答解析：只提取 A 记录（IP）与包含 "15740"/"apps" 的可读文本。
     *
     * 不做完整 DNS 解析 —— 我们只需要知道「谁应答了、里面有没有 ptp、apps 是什么」。
     */
    private fun parseMdnsResponse(data: ByteArray, len: Int): String {
        val sb = StringBuilder()
        // 逐个扫 A 记录（TYPE=1, CLASS=1, RDLENGTH=4）
        var i = 0
        while (i + 12 < len) {
            val type = ((data[i + 4].toInt() and 0xFF) shl 8) or (data[i + 5].toInt() and 0xFF)
            val rdlen = ((data[i + 10].toInt() and 0xFF) shl 8) or (data[i + 11].toInt() and 0xFF)
            if (type == 1 && rdlen == 4 && i + 16 <= len) {
                val ip = "${data[i + 12].toInt() and 0xFF}.${data[i + 13].toInt() and 0xFF}." +
                    "${data[i + 14].toInt() and 0xFF}.${data[i + 15].toInt() and 0xFF}"
                sb.appendLine("  A 记录 → $ip")
            }
            i++
        }
        // 扫描可读 ASCII 片段，找 ptp / apps / 端口号
        val text = StringBuilder()
        for (k in 0 until len) {
            val c = data[k].toInt() and 0xFF
            if (c in 0x20..0x7E) {
                text.append(c.toChar())
            } else {
                text.append(' ')
            }
        }
        val printable = text.toString()
        for (kw in listOf("_ptp", "apps=", "ver=", "seq=", "pid=", "vid=", "guid=", "Z_", "NIKON")) {
            var idx = printable.indexOf(kw)
            while (idx >= 0) {
                val end = (idx + 60).coerceAtMost(printable.length)
                val seg = printable.substring(idx, end).trim()
                if (seg.isNotEmpty()) sb.appendLine("  文本: $seg")
                idx = printable.indexOf(kw, idx + kw.length)
                if (sb.length > 1200) break
            }
        }
        return sb.toString()
    }

    /**
     * 并发扫描端口范围，找出真正开放的那些。
     *
     * 为什么用并发：串行扫 1000 个端口 × 300ms 要 5 分钟，并发 64 路只要几秒。
     * 只对「连上了」的端口做记录，被拒绝/超时的全部忽略。
     *
     * @param from 起始端口（含）
     * @param to   结束端口（含）
     */
    fun scanRangeConcurrent(
        host: String,
        network: Network?,
        from: Int = 1,
        to: Int = 10000,
        threads: Int = 32,
        timeoutMs: Int = 200,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Int> {
        val open = java.util.Collections.synchronizedList(ArrayList<Int>())
        val ports = java.util.concurrent.atomic.AtomicInteger(from)
        val total = to - from + 1
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = ArrayList<Thread>()
        for (w in 0 until threads) {
            val t = Thread {
                while (true) {
                    val p = ports.getAndIncrement()
                    if (p > to) break
                    var sock: java.net.Socket? = null
                    try {
                        val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
                        sock = s
                        try {
                            network?.bindSocket(s)
                        } catch (_: Throwable) {
                        }
                        s.connect(java.net.InetSocketAddress(host, p), timeoutMs)
                        open.add(p)
                        Logx.i(TAG, "★ 发现开放端口 $host:$p")
                    } catch (_: Throwable) {
                        // 拒绝/超时都视为关闭
                    } finally {
                        try {
                            sock?.close()
                        } catch (_: Throwable) {
                        }
                    }
                    val d = done.incrementAndGet()
                    if (d % 500 == 0) onProgress(d, total)
                }
            }
            t.isDaemon = true
            t.start()
            workers.add(t)
        }
        for (t in workers) {
            try {
                t.join(60_000)
            } catch (_: Throwable) {
            }
        }
        val result = synchronized(open) { open.toList() }.sorted()
        Logx.i(TAG, "端口扫描完成 $host: $from-$to，开放 ${result.size} 个: $result")
        return result
    }

    /** 单次 TCP 探测结果。 */
    data class PortHit(val host: String, val port: Int, val outcome: String, val open: Boolean)

    /**
     * 扫描一个主机上的候选端口，找出真正开放的那个。
     *
     * 为什么需要：实测 `192.168.1.1` 是**明确拒绝**（70ms 内 ECONNREFUSED），
     * 说明主机活着、路由正确，只是那个端口没在监听。
     * 尼康 Z 系列的 PTP/IP 端口并不总是 15740，只能实扫。
     *
     * 注意：连上就断属于协议违规，但对**非 PTP 端口**无影响；
     * 对 PTP 端口我们只在拿不到任何开放端口时才扫，且立刻用完整握手接管。
     */
    fun scanPorts(
        host: String,
        network: Network?,
        ports: IntArray = CANDIDATE_PORTS,
        timeoutMs: Int = 1200,
    ): List<PortHit> {
        val out = ArrayList<PortHit>()
        for (p in ports) {
            var sock: java.net.Socket? = null
            val hit = try {
                val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
                sock = s
                network?.bindSocket(s)
                s.connect(java.net.InetSocketAddress(host, p), timeoutMs)
                PortHit(host, p, "开放", true)
            } catch (t: Throwable) {
                PortHit(host, p, t.javaClass.simpleName, false)
            } finally {
                try {
                    sock?.close()
                } catch (_: Throwable) {
                }
            }
            if (hit.open) Logx.i(TAG, "发现开放端口 $host:${hit.port}")
            out.add(hit)
        }
        return out
    }

    /**
     * AP 模式（相机开热点）下为什么连不上 —— 结论与后续建议。
     *
     * 实测：Z6II 在「连接至智能设备 → Wi-Fi 模式」下，相机确实会
     * 开启热点、分配 DHCP（手机拿到 192.168.1.x）、网关 192.168.1.1 在线，
     * 但 15740/15741/15742/45204 四个端口在 37~50ms 内一律 ECONNREFUSED。
     *
     * 也就是说：**AP 模式只提供网络接入，不提供 PTP/IP 服务。**
     * 这个模式是给 SnapBridge 传小图用的，它走的是 SnapBridge 私有协议，
     * 不是标准 PTP/IP。
     *
     * 可带出门、又不锁机的路子只剩蓝牙（BLE）与音频触发，
     * 这两条都不需要相机进入任何"受控"模式。
     */
    fun apModeExplanation(): String = """
        === 为什么 Wi-Fi 热点模式连不上 ===
        
        实测结论：Z6II 的「连接至智能设备 → Wi-Fi 模式」只提供网络接入：
        
        ✓ 相机会开热点，手机能连上
        ✓ 相机分配 DHCP，手机拿到 192.168.1.x
        ✓ 网关 192.168.1.1 在线（拒绝包有回应，说明主机活着）
        ✗ 15740 / 15741 / 15742 / 45204 全部 37~50ms 内 ECONNREFUSED
        
        即：**该模式不开放 PTP/IP 服务**，它给 SnapBridge 用的是尼康私有协议。
        
        而「连接到 PC → 照相机控制」虽然开放 PTP/IP，
        但那是受控模式，会锁住机身快门 —— 不符合本 App 的用途。
        
        所以外出使用时，可用的只有：
        · 蓝牙 BLE（不锁机，但协议私有，数据层尚未打通）
        · 音频触发（不连相机，零侵入，已实现并支持一键校准）
    """.trimIndent()

    /**
     * UDP 广播/组播发现（无副作用，最安全的找相机方式）。
     *
     * 尼康机身监听 UDP 5353 的 mDNS 服务广播（`_ptp._tcp`），
     * 部分固件还会应答 15740/UDP 的探测包。这里两种都发，
     * 把**任何回了包的主机**当成相机候选。
     *
     * 相比 TCP 端口扫描：不发 TCP、不建连接、不会被相机记成异常客户端。
     */
    fun udpDiscover(network: Network?, timeoutMs: Int = 2500): List<String> {
        val found = LinkedHashSet<String>()
        var sock: java.net.DatagramSocket? = null
        try {
            sock = java.net.DatagramSocket().apply {
                network?.bindSocket(this)
                broadcast = true
                soTimeout = timeoutMs
                reuseAddress = true
            }
            val local = localAddresses().firstOrNull { it.isWifi }?.ipv4
            val base = local?.split(".")?.takeIf { it.size == 4 }
                ?.let { "${it[0]}.${it[1]}.${it[2]}" }

            // 1) mDNS 查询 PTP 服务
            val mdnsQuery = byteArrayOf(
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x04, '_'.code.toByte(), 'p'.code.toByte(), 't'.code.toByte(), 'p'.code.toByte(),
                0x04, '_'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'p'.code.toByte(),
                0x05, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(),
                'l'.code.toByte(),
                0x00, 0x00, 0x0C, 0x00, 0x01,
            )

            val targets = ArrayList<java.net.InetSocketAddress>()
            try {
                targets.add(
                    java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("224.0.0.251"), 5353
                    )
                )
            } catch (_: Throwable) {
            }
            try {
                targets.add(
                    java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("255.255.255.255"), 5353
                    )
                )
            } catch (_: Throwable) {
            }
            if (base != null) {
                try {
                    targets.add(
                        java.net.InetSocketAddress(
                            java.net.InetAddress.getByName("$base.255"), 15740
                        )
                    )
                } catch (_: Throwable) {
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            for (t in targets) {
                try {
                    sock.send(java.net.DatagramPacket(mdnsQuery, mdnsQuery.size, t))
                } catch (e: Throwable) {
                    Logx.w(TAG, "UDP 发送到 $t 失败: ${e.message}")
                }
            }

            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val from = pkt.address?.hostAddress ?: continue
                    if (from != "0.0.0.0") {
                        found.add(from)
                        Logx.i(TAG, "UDP 应答来自 $from (${pkt.length} 字节)")
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (e: Throwable) {
                    break
                }
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "UDP 发现失败: ${t.message}")
        } finally {
            try {
                sock?.close()
            } catch (_: Throwable) {
            }
        }
        return found.toList()
    }
}
