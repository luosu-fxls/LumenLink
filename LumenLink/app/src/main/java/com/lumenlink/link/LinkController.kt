package com.lumenlink.link

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.lumenlink.App
import com.lumenlink.core.Logx
import com.lumenlink.flash.FlashlightController
import com.lumenlink.nikon.NikonCamera
import com.lumenlink.nikon.NikonEvents
import com.lumenlink.ptp.PtpIpTransport
import com.lumenlink.ptp.PtpSession
import com.lumenlink.ptp.PtpUsbTransport
import com.lumenlink.service.FlashLinkService
import com.lumenlink.sync.AudioTrigger
import com.lumenlink.sync.LightMode
import com.lumenlink.sync.SyncConfig
import com.lumenlink.sync.SyncEngine
import com.lumenlink.sync.SyncState
import com.lumenlink.sync.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LinkState { IDLE, CONNECTING, CONNECTED, ERROR }

data class AppUiState(
    val linkState: LinkState = LinkState.IDLE,
    val linkDetail: String = "",
    val transport: String = "",
    val cameraModel: String = "",
    val propsFound: Int = 0,
    val shutterSpeed: String = "",
    val shutterChoices: List<String> = emptyList(),
    val shutterWritable: Boolean = false,
    val iso: String = "",
    val isoChoices: List<String> = emptyList(),
    val torch: FlashlightController.TorchState = FlashlightController.TorchState(),
    val sync: SyncState = SyncState(),
    val audioRunning: Boolean = false,
    val audioLevel: Float = 0f,
    val message: String? = null,
)

/**
 * 旁听模式的运行状态。
 *
 * 这个模式**完全不碰相机**：不调 PTP、不改参数、不锁相机机身。
 * 用户在相机上自己调快门/光圈/ISO，App 只负责"听到快门声就对 LED 做出反应"。
 */
data class BystanderState(
    val listening: Boolean = false,
    val lightArmed: Boolean = false,
    val triggerCount: Int = 0,
    val lastRms: Float = 0f,
    val level: Float = 0f,
    /** 当前生效阈值 —— 用来显示"离触发还差多少"。 */
    val threshold: Float = 0f,
    /** 当前块峰值。 */
    val peak: Float = 0f,
    /** 当前噪声底估计，用于判断麦克风收音质量。 */
    val noiseFloor: Float = 0f,
    /** 当前频谱摘要，如 "flat=0.42 高=0.55 低=0.20"。 */
    val spectrumInfo: String = "",
    /** 是否处于频谱采样状态。 */
    val sampling: Boolean = false,
    /** 已采到的样本数。 */
    val sampleCount: Int = 0,
    val sensitivity: Float = 0.75f,
    val lastTriggerAtMillis: Long = 0,
    val message: String? = null,
    /** 最近采到的波形（用于 UI 可视化，最多保留 5 条）。 */
    val recentWaveforms: List<FloatArray> = emptyList(),
    /** 降噪是否开启。 */
    val denoiseEnabled: Boolean = true,
    /** 降噪强度 0..1。 */
    val denoiseStrength: Float = 0.7f,
    /** 降噪带来的信噪比变化（dB，负值=噪声被压低）。 */
    val denoiseDb: Float = 0f,
    /** 实际使用的录音源名称。 */
    val audioSource: String = "",
    /** 采样阶段的人类可读描述，如"环境稳定中…"/"可以按快门了"/"已结束"。 */
    val samplePhase: String = "",
    /** 音色模板是否已训练。 */
    val timbreTrained: Boolean = false,
    /** 音色相似度门限（0..1），未训练时为 0。 */
    val timbreGate: Float = 0f,
    /** 采样目标条数（取值见 SampleRange，默认 8）。 */
    val sampleTarget: Int = 8,
    /** 采样时最近几条频谱形状（0..1 归一化），用于可视化。 */
    val recentSpectra: List<FloatArray> = emptyList(),
    /** 历史总触发次数（跨重启累计，可在设置里清零）。 */
    val lifetimeTriggers: Int = 0,
    /** 实时频谱形状（未采样时也可看）。 */
    val liveSpectrum: FloatArray = FloatArray(0),
)
private data class LinkInfo(
    val state: LinkState = LinkState.IDLE,
    val detail: String = "",
    val transport: String = "",
    val model: String = "",
    val props: Int = 0,
    val shutter: String = "",
    val shutterChoices: List<String> = emptyList(),
    val shutterWritable: Boolean = false,
    val iso: String = "",
    val isoChoices: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * 全局编排器：把闪光灯、PTP 相机、同步引擎、音频触发串起来。
 * 放在 Application 作用域，这样 Activity 死了、锁屏了，补光和连接都还在（服务只负责保活）。
 */
class LinkController(private val app: App, private val skipInit: Boolean = false) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 构造期失败信息（降级模式时非空）。 */
    @Volatile
    var initFailure: String? = null

    val flash = FlashlightController(app)
    val sync = SyncEngine(flash, scope)
    val audio = AudioTrigger(app)

    /** 蓝牙探测（只读枚举服务，不做鉴权） */
    val ble = com.lumenlink.net.BleProbe(app)

    /** 尼康 BLE 配对（配对后相机才开放 WiFi PTP/IP）。 */
    val blePairing = com.lumenlink.net.NikonBlePairing(app)

    private val _link = MutableStateFlow(LinkInfo())
    private val _audioRunning = MutableStateFlow(false)
    private val _audioLevel = MutableStateFlow(0f)

    /** 跨 App 重连时要复用的相机热点网络（进程内有效）。 */
    @Volatile
    private var cameraNetwork: Network? = null

    /** 最近一次连上的相机热点 SSID（跨进程重启后用来重新请求网络）。 */
    @Volatile
    private var cameraSsid: String? = null

    /** 已绑定的专属网络是否仍然有效（系统可能回收）。 */
    @Volatile
    private var cameraNetworkAlive: Boolean = false

    /** 当前进程内到该网络的组播权限请求（PTP/IP 事件通道依赖它）。 */
    private var multicastCallback: ConnectivityManager.NetworkCallback? = null

    val uiState: StateFlow<AppUiState> =
        combine(_link, flash.state, sync.state, _audioRunning) { link, torch, syncState, audioOn ->
            AppUiState(
                linkState = link.state,
                linkDetail = link.detail,
                transport = link.transport,
                cameraModel = link.model,
                propsFound = link.props,
                shutterSpeed = link.shutter,
                shutterChoices = link.shutterChoices,
                shutterWritable = link.shutterWritable,
                iso = link.iso,
                isoChoices = link.isoChoices,
                torch = torch,
                sync = syncState,
                audioRunning = audioOn,
                audioLevel = _audioLevel.value,
                message = link.message,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AppUiState())

    private var session: PtpSession? = null
    private var camera: NikonCamera? = null
    private var lastUsbTransport: PtpUsbTransport? = null
    private var pendingUsbDevice: UsbDevice? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var linkedNetwork: Network? = null

    init {
        // 每一步都单独兜住：任何一个组件初始化失败都不该让 App 起不来。
        try {
            flash.start()
        } catch (t: Throwable) {
            Logx.e(TAG, "闪关灯回调注册失败: ${t.message}", t)
        }
        try {
            scope.launch {
                try {
                    flash.refresh()
                } catch (t: Throwable) {
                    Logx.e(TAG, "枚举闪光灯失败: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "启动闪光灯枚举任务失败: ${t.message}", t)
        }
    }

    // ---------------- 连接：Wi-Fi ----------------

    /**
     * **不抢网络**的连接方式：直接使用系统当前已连的 Wi-Fi。
     *
     * 为什么需要它：SnapBridge 连上相机时，相机才真正开放服务。
     * 而 LumenLink 一旦用 `requestNetwork` 建自己的专属网络，
     * SnapBridge 就会掉线，服务随之关闭 —— 我们永远看不到端口。
     *
     * 这个模式不调用 requestNetwork，只读取系统现有的 Wi-Fi 网络对象，
     * 然后用它去扫端口。目的是**趁 SnapBridge 还连着时**看清相机到底开了什么。
     *
     * 注意：此时所有 App 共用同一个网络，可能互相干扰，
     * 所以只适合做诊断，不适合长期使用。
     */
    fun useExistingWifiForDiag(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 使用系统当前 Wi-Fi（不抢网络）===")
        val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
        if (routes.isEmpty()) {
            sb.appendLine("系统当前没有 Wi-Fi 连接。")
            sb.appendLine("请先在系统设置里手动连上相机热点（或让 SnapBridge 连上）。")
            return sb.toString()
        }
        val best = routes.maxByOrNull { r -> if (r.ssidHint == "无外网") 4 else 0 } ?: routes.first()
        sb.appendLine("采用网络: ${best.describe()}")
        cameraNetwork = best.network
        cameraNetworkAlive = true
        sb.appendLine("已设为诊断用网络（不调用 requestNetwork）")
        sb.appendLine()
        sb.appendLine("下一步：点「★ 全端口快扫」即可用这个网络去扫。")
        Logx.i(TAG, "诊断模式使用现有 Wi-Fi: ${best.describe()}")
        return sb.toString()
    }

    /**
     * 应用内连接相机热点（根本解法）。
     *
     * 为什么必须要这一步：
     *
     * 从安卓 10 起，“把没有外网的 Wi-Fi 设为默认网络”这件事只对**系统设置**
     * 和**自己 requestNetwork 建立的网络**开放。用户手动在设置里连相机热点时，
     * Android **不会**把它设为默认网络（否则手机就没网了），于是 App 里的
     * `Socket()` 默认出口仍然是蜂窝 —— 这正是日志里
     * `from /:: (port 0)`、`ENETUNREACH`、`ECONNREFUSED` 的成因。
     *
     * `WifiNetworkSpecifier` 由本 App 自己发起连接，拿到的 [Network] 归本 App 专属，
     * `bindSocket` 到它上面就有确定的路由，不需要它当默认网络，也不需要关移动数据。
     *
     * 代价：系统会弹一次「LumenLink 想要连接 WLAN 网络」的确认框，等你点「连接」。
     *
     * @param ssid 相机热点 SSID（可传前缀，如 `NIKON`）
     * @param passphrase 密码；相机热点通常为空（开放网络）
     * @param thenConnect 拿到网络后是否立刻走完整的 PTP 连接流程
     */
    fun connectCameraWifi(
        ssid: String,
        passphrase: String = "",
        thenConnect: Boolean = true,
    ) {
        val clean = ssid.trim()
        if (clean.isEmpty()) {
            _link.value = _link.value.copy(
                message = "请先填写相机热点的 SSID（相机屏幕/机身上能看到，形如 NIKON Z 6_2 或 Z_6_2_8116255）",
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _link.value = _link.value.copy(
                message = "应用内连热点需要 Android 10+。请在系统设置里手动连接相机热点后再点「自动搜索并连接」。",
            )
            return
        }
        _link.value = _link.value.copy(
            state = LinkState.CONNECTING,
            detail = "正在连接无线网络 $clean …",
            message = null,
        )
        Logx.i(TAG, "requestNetwork: ssid=$clean")
        com.lumenlink.net.WifiJoiner.requestCameraNetwork(
            context = app,
            ssid = clean,
            passphrase = passphrase,
        ) { result ->
            val net = result.network
            if (net == null) {
                Logx.w(TAG, "连热点失败: ${result.message}")
                fail("连接相机热点失败：${result.message}")
                return@requestCameraNetwork
            }
            cameraNetwork = net
            cameraSsid = clean
            cameraNetworkAlive = true
            Logx.i(TAG, "已拿到专属网络: $net")
            bindNetworkCallbacks(net)
            _link.value = _link.value.copy(detail = "网络已就绪 ${net}", message = null)
            if (thenConnect) {
                // 注意 force=true：此刻 state 就是 CONNECTING，
                // 不带 force 会被 connectWifiAuto 的重入保护直接挡掉（v0.22.0 的 bug）。
                connectWifiAuto(force = true)
            } else {
                _link.value = _link.value.copy(
                    state = LinkState.IDLE,
                    detail = "网络已绑定",
                    message = "网络已绑定，可以点「自动搜索并连接」",
                )
            }
        }
    }

    /**
     * 监听专属网络的存活状态。
     *
     * 目的只有一个：网络被系统回收后不要把 socket 绑在一个死 Network 上反复重试。
     * （组播权限对 TCP 不是必需的，这里不做额外请求，免得再弹窗）
     */
    private fun bindNetworkCallbacks(net: Network) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Logx.w(TAG, "没有 ConnectivityManager，跳过网络回调")
            return
        }
        try {
            multicastCallback?.let { old ->
                try {
                    cm.unregisterNetworkCallback(old)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                cameraNetworkAlive = false
                Logx.w(TAG, "专属网络已断开: $network")
                _link.value = _link.value.copy(
                    detail = "相机热点已断开",
                    message = "相机热点连接已断开，请重新点「连接相机热点（应用内）」",
                )
            }
        }
        multicastCallback = cb
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
            Logx.i(TAG, "已注册网络存活回调")
        } catch (t: Throwable) {
            Logx.w(TAG, "注册网络回调失败: ${t.message}")
        }
    }

    /**
     * 专属网络拿不到 LinkProperties 时的兜底候选地址。
     *
     * 实测小米 Android 16 上 specifier 网络的 LinkProperties 可能为空，
     * 但本机 IP 一定能从网卡枚举里读到（192.168.1.3），据此推同网段 .1。
     */
    private fun fallbackHosts(): List<String> {
        val set = LinkedHashSet<String>()
        for (i in com.lumenlink.net.CameraDiscovery.localAddresses()) {
            if (!i.isWifi) continue
            val parts = i.ipv4.split(".")
            if (parts.size == 4) {
                set.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
            }
        }
        set.addAll(listOf("192.168.1.1", "192.168.0.1", "10.0.0.1"))
        return set.toList()
    }

    /**
     * 自动发现并连接相机（用户只需连上相机热点，不用输 IP）。
     *
     * 两个关键点：
     *
     * 1. **不做裸 TCP 端口探测**。PTP/IP 要求连上后立即发 InitCommandRequest，
     *    连上就断会被相机判为异常客户端，严重时相机直接退出 Wi-Fi 模式。
     *    所以对每个候选地址执行**完整的一次握手**，失败才换下一个。
     *
     * 2. **socket 必须绑定到 Wi-Fi 那个 Network**。相机热点没有外网，
     *    Android 默认会把 socket 出口指向蜂窝/家用 Wi-Fi，导致 refused / 超时 /
     *    ENETUNREACH（用户实测都出现过）。见 PtpIpTransport 的 bindSocket。
     */
    fun connectWifiAuto(force: Boolean = false) {
        if (!force && _link.value.state == LinkState.CONNECTING) {
            Logx.w(TAG, "connectWifiAuto 被跳过：当前已在连接中（state=CONNECTING）")
            return
        }
        _link.value = _link.value.copy(
            state = LinkState.CONNECTING,
            detail = "正在搜索相机…",
            message = null,
        )
        scope.launch {
            try {
                val local = com.lumenlink.net.CameraDiscovery.localAddresses()
                Logx.i(TAG, "本机网卡: " + local.joinToString { "${it.name}=${it.ipv4}" })

                val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
                Logx.i(TAG, "Wi-Fi 网络数: ${routes.size}")
                routes.forEach { Logx.i(TAG, "  ${it.describe()}") }

                // 如果用户先用「应用内连接相机热点」拿到了专属网络，就优先走它。
                var bound = cameraNetwork
                if (bound != null && !cameraNetworkAlive) {
                    Logx.w(TAG, "已绑定的专属网络已失效，改用默认路由")
                    bound = null
                    cameraNetwork = null
                }
                if (bound != null) Logx.i(TAG, "优先使用已绑定的专属网络: $bound")

                // 前置判断：没连上相机热点就直接说清楚，别浪费十几秒试一堆必然失败的地址
                if (bound == null) {
                    val hint = com.lumenlink.net.CameraDiscovery.notCameraHotspotHint(app)
                    if (hint != null) {
                        Logx.w(TAG, "前置检查未通过：$hint")
                        fail(hint)
                        return@launch
                    }
                }

                var hosts = com.lumenlink.net.CameraDiscovery.candidateHosts(app)
                if (hosts.isEmpty()) {
                    hosts = fallbackHosts()
                    Logx.w(TAG, "candidateHosts 为空，用本机 IP 推导兜底: $hosts")
                }
                if (hosts.isEmpty()) {
                    fail(
                        "算不出候选相机地址。\n" +
                            "当前所有网卡: " + local.joinToString { "${it.name}=${it.ipv4}" } + "\n" +
                            "建议先用「应用内连接相机热点」把网络绑定好。"
                    )
                    return@launch
                }
                Logx.i(TAG, "候选地址: ${hosts.joinToString()}")

                val guid = com.lumenlink.core.ClientIdentity.guid(app)
                val name = com.lumenlink.core.ClientIdentity.friendlyName(app)

                var opened: PtpSession? = null
                var usedHost = ""

                val ok = withContext(Dispatchers.IO) {
                    com.lumenlink.net.CameraDiscovery.tryEachHost(
                        hosts = hosts,
                        onProgress = { msg ->
                            _link.value = _link.value.copy(detail = msg)
                        },
                    ) { host ->
                        var s: PtpSession? = null
                        try {
                            val route = com.lumenlink.net.CameraDiscovery
                                .networkFor(listOf(host), routes)
                            // 专属网络优先：它一定归本 App，路由最确定
                            val plane = bound ?: route?.network
                            Logx.i(
                                TAG,
                                "尝试 $host 走 ${
                                    if (plane != null) "network=$plane" else "(默认网络)"
                                }"
                            )
                            // 端口不再固定：尼康不同固件用的端口不一样。
                            // 先试 15740（标准），不通再扫其余候选端口。
                            var usedPort = -1
                            for (p in com.lumenlink.net.CameraDiscovery.CANDIDATE_PORTS) {
                                val transport = PtpIpTransport(
                                    host = host,
                                    port = p,
                                    friendlyName = name,
                                    clientGuid = guid,
                                    network = plane,
                                )
                                val sess = PtpSession(transport)
                                try {
                                    sess.connectAndOpen()
                                    s = sess
                                    opened = sess
                                    usedHost = host
                                    usedPort = p
                                    break
                                } catch (t: Throwable) {
                                    Logx.w(TAG, "$host:$p 握手失败: ${t.message}")
                                    // 失败就关掉，别留着半开连接干扰下一个候选
                                    try {
                                        sess.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            if (usedPort > 0) {
                                Logx.i(TAG, "✓ 已连接 $host:$usedPort")
                                return@tryEachHost true
                            }
                            false
                        } catch (t: Throwable) {
                            Logx.w(TAG, "$host 尝试异常: ${t.message}")
                            try {
                                s?.close()
                            } catch (_: Throwable) {
                            }
                            false
                        }
                    }
                }

                val openedS = opened
                if (ok == null || openedS == null) {
                    fail(
                        "连接相机失败。已尝试: " + hosts.joinToString(", ") + "\n" +
                            "Wi-Fi 网络: " + routes.joinToString { "${it.localIp}/gw=${it.gateway ?: "-"}" }
                    )
                    return@launch
                }

                closeQuietly()
                session = openedS
                onSessionReady(openedS, "Wi-Fi PTP/IP $usedHost")
            } catch (t: Throwable) {
                fail("Wi-Fi 连接失败: ${t.message}")
            }
        }
    }

    fun connectWifi(host: String, port: Int = PtpIpTransport.DEFAULT_PORT, network: Network? = null) {
        if (_link.value.state == LinkState.CONNECTING) return
        linkedNetwork = network
        val guid = com.lumenlink.core.ClientIdentity.guid(app)
        val name = com.lumenlink.core.ClientIdentity.friendlyName(app)
        Logx.i(TAG, "客户端身份: \"$name\" guid=${com.lumenlink.core.ClientIdentity.currentGuidText(app)}")
        _link.value = _link.value.copy(state = LinkState.CONNECTING, detail = "连接 $host:$port …", message = null)
        scope.launch {
            try {
                closeQuietly()
                val transport = withContext(Dispatchers.IO) {
                    PtpIpTransport(
                        host = host,
                        port = port,
                        network = network,
                        friendlyName = name,
                        clientGuid = guid,
                    )
                }
                val s = PtpSession(transport)
                withContext(Dispatchers.IO) { s.connectAndOpen() }
                session = s
                onSessionReady(s, "Wi-Fi PTP/IP $host:$port")
            } catch (t: Throwable) {
                fail("Wi-Fi 连接失败: ${t.message}")
            }
        }
    }

    // ---------------- 连接：USB ----------------

    fun connectUsb() {
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            fail("本机没有 USB Host 支持")
            return
        }
        val devices = PtpUsbTransport.findPtpDevices(usb)
        Logx.i(TAG, "USB 设备: " + devices.joinToString { PtpUsbTransport.describeDevice(it) })
        if (devices.isEmpty()) {
            fail("未检测到 PTP 相机。请确认：相机 USB 模式设为 PTP/MTP、使用 OTG 转接头、线材支持数据传输。")
            return
        }
        val dev = devices.first()
        if (usb.hasPermission(dev)) {
            openUsb(usb, dev)
        } else {
            pendingUsbDevice = dev
            registerUsbReceiver()
            _link.value = _link.value.copy(
                state = LinkState.CONNECTING,
                detail = "等待 USB 授权…（请在弹出的对话框点允许）"
            )
            try {
                usb.requestPermission(dev, PtpUsbTransport.buildPermissionIntent(app, dev))
            } catch (t: Throwable) {
                fail("请求 USB 权限失败: ${t.message}")
            }
        }
    }

    private fun registerUsbReceiver() {
        if (usbReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != PtpUsbTransport.permissionAction()) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val dev = pendingUsbDevice
                pendingUsbDevice = null
                Logx.i(TAG, "USB 权限结果 granted=$granted")
                if (granted && dev != null) {
                    val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager
                    if (usb != null) openUsb(usb, dev) else fail("UsbManager 丢失")
                } else {
                    fail("USB 权限被拒绝")
                }
            }
        }
        usbReceiver = r
        val filter = IntentFilter(PtpUsbTransport.permissionAction())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
    }

    private fun openUsb(usb: UsbManager, dev: UsbDevice) {
        _link.value = _link.value.copy(state = LinkState.CONNECTING, detail = "打开 USB 相机…")
        scope.launch {
            try {
                closeQuietly()
                // 关键：先把手电筒关掉再连 USB。
                // 部分机型上 Camera2 的 torch 会占用 ISP/相机总线资源，
                // 而 USB 的 PHY 与相机子系统在同一供电域，开灯期间做 USB 传输会互相干扰。
                // 先关灯 → 连好 USB → 再按需恢复补光，顺序不能反。
                if (flash.state.value.on) {
                    Logx.i(TAG, "USB 连接前先关闭手电筒（避免与相机子系统争用）")
                    flash.turnOff()
                    kotlinx.coroutines.delay(200)
                }
                val transport = PtpUsbTransport(
                    usbManager = usb,
                    device = dev,
                    overrideDataInAddr = usbDataInOverride,
                    overrideEventAddr = usbEventOverride,
                )
                val s = PtpSession(transport)
                withContext(Dispatchers.IO) { s.connectAndOpen() }
                session = s
                lastUsbTransport = transport
                onSessionReady(s, "USB PTP")
            } catch (t: Throwable) {
                fail("USB 连接失败: ${t.message}")
            }
        }
    }

    /** 手工指定 USB 端点（十六进制地址，如 0x81）。null = 自动探测。 */
    @Volatile
    private var usbDataInOverride: Int? = null

    @Volatile
    private var usbEventOverride: Int? = null

    fun setUsbEndpointOverrides(dataIn: Int?, event: Int?) {
        usbDataInOverride = dataIn
        usbEventOverride = event
        Logx.i(TAG, "USB 端点覆盖: dataIn=$dataIn event=$event（下次连接生效）")
    }

    /**
     * 裸 USB 健康检查（不经过 PTP 逻辑）。
     * 用于区分「USB 链路问题」和「PTP 实现问题」——这两类的修法完全不同。
     */
    fun runUsbHealthCheck(): String {
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return "无 UsbManager"
        val devs = PtpUsbTransport.findPtpDevices(usb)
        if (devs.isEmpty()) return "未检测到 PTP 类 USB 设备（相机没插好或没在 PTP 模式）"
        val dev = devs.first()
        if (!usb.hasPermission(dev)) return "没有 USB 权限，请先点「USB 连接」并在弹窗里允许"
        // 释放当前会话，避免同一台设备被两处同时打开
        closeQuietly()
        return try {
            PtpUsbTransport(usb, dev).usbHealthCheck()
        } catch (t: Throwable) {
            "健康检查异常: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 打印当前 USB 设备与端点，供排查用。 */
    fun dumpUsbEndpoints(): String {
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return "无 UsbManager"
        val devs = PtpUsbTransport.findPtpDevices(usb)
        if (devs.isEmpty()) return "未检测到 PTP 类 USB 设备"
        val sb = StringBuilder()
        for (d in devs) {
            sb.appendLine(PtpUsbTransport.describeDevice(d))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                sb.append("  iface ${itf.id} class=${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol}")
                sb.appendLine(" ep=${itf.endpointCount}")
                for (j in 0 until itf.endpointCount) {
                    val e = itf.getEndpoint(j)
                    val dir = if (e.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val kind = when (e.type) {
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        else -> "OTHER"
                    }
                    sb.appendLine("    ep 0x%02X %s %s maxPacket=%d".format(e.address, dir, kind, e.maxPacketSize))
                }
            }
        }
        return sb.toString()
    }

    // ---------------- 会话就绪后的初始化 ----------------

    private suspend fun onSessionReady(s: PtpSession, transportLabel: String) {
        val info = s.deviceInfo
        val cam = NikonCamera(s)
        camera = cam

        // 事件通道 -> 快门同步
        s.eventListener = { e ->
            NikonEvents.observe(e.code)
            Logx.i(TAG, "相机事件 0x%04X (${NikonEvents.describe(e.code)})".format(e.code))
            if (NikonEvents.isShutterTrigger(e.code)) {
                Logx.i(TAG, "判定为快门触发，来源=PTP事件")
                sync.onTrigger(TriggerSource.CAMERA_PTP_EVENT)
            }
        }

        val discovered = try {
            withContext(Dispatchers.IO) {
                // 先尝试切到遥控模式：部分机型必须先切模式才允许改参数/释放快门
                cam.enterRemoteMode()
                cam.discover()
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "属性枚举失败: ${t.message}")
            emptyList()
        }

        val push = s.pushEventsActive
        Logx.i(TAG, "事件通道: ${if (push) "推送可用" else "不可用（将依赖音频触发）"}")

        if (cam.accessLooksRestricted) {
            _link.value = _link.value.copy(
                state = LinkState.ERROR,
                transport = transportLabel,
                model = info?.model?.takeIf { it.isNotBlank() } ?: "未知机型",
                detail = "已握手但属性全被拒：鉴权/配对未完成",
                message = "相机接受了连接，但拒绝了所有属性读取。这几乎总是「配对」问题，不是机型不支持。\n" +
                    "Z 系做法：相机 网络菜单 → 网络设定 → 删除当前配置文件后重建（直连/AP 模式），" +
                    "再回本 App 重新连接。注意每次重连必须用同一身份（本机 GUID 已持久化）。",
            )
            return
        }

        // 读取候选值与当前值可能触发 PTP 事务（阻塞 IO），统一切到 IO 线程
        val snapshot = withContext(Dispatchers.IO) {
            val sc = cam.shutterChoices()
            val ic = cam.isoChoices()
            CameraSnapshot(
                shutters = sc,
                isos = ic,
                currentShutter = cam.readShutter().ifBlank { sc.firstOrNull() ?: "" },
                currentIso = cam.readIso(),
            )
        }
        Logx.i(TAG, "快门候选值 ${snapshot.shutters.size} 个, ISO 候选值 ${snapshot.isos.size} 个")

        _link.value = _link.value.copy(
            state = LinkState.CONNECTED,
            detail = buildString {
                append(if (cam.shutterWritable) "已连接" else "已连接（本机身不允许遥控改快门）")
                append(" · 事件").append(if (push) "推送可用" else "不可用，请开音频触发")
            },
            transport = transportLabel,
            model = info?.model?.takeIf { it.isNotBlank() } ?: (info?.vendorExtensionDesc ?: "未知机型"),
            props = discovered.size,
            shutter = snapshot.currentShutter,
            shutterChoices = snapshot.shutters,
            shutterWritable = cam.shutterWritable,
            iso = snapshot.currentIso,
            isoChoices = snapshot.isos,
            message = if (discovered.isEmpty()) "已连接但没读到属性表，请点「复制诊断」查看原始日志" else null,
        )
    }

    private data class CameraSnapshot(
        val shutters: List<String>,
        val isos: List<String>,
        val currentShutter: String,
        val currentIso: String,
    )

    fun disconnect() {
        scope.launch {
            try {
                sync.disarm()
            } catch (_: Throwable) {
            }
            stopAudio()
            closeQuietly()
            _link.value = LinkInfo(state = LinkState.IDLE, detail = "未连接")
            FlashLinkService.stop(app)
        }
    }

    private fun closeQuietly() {
        try {
            session?.close()
        } catch (t: Throwable) {
            Logx.w(TAG, "关闭会话异常: ${t.message}")
        }
        session = null
        camera = null
    }

    private fun fail(msg: String) {
        Logx.e(TAG, msg)
        _link.value = _link.value.copy(state = LinkState.ERROR, detail = msg, message = msg)
    }

    // ---------------- 补光控制 ----------------

    fun setLightMode(mode: LightMode) = sync.setMode(mode)

    fun updateConfig(cfg: SyncConfig) = sync.updateConfig(cfg)

    fun arm() {
        if (!hasCameraPermission()) {
            fail("请先授予相机权限（手电筒需要 CAMERA 权限）")
            return
        }
        FlashLinkService.start(app)
        scope.launch {
            try {
                sync.arm()
            } catch (t: Throwable) {
                fail("开启补光失败: ${t.message}")
            }
        }
    }

    fun disarm() {
        scope.launch {
            try {
                sync.disarm()
            } catch (t: Throwable) {
                Logx.e(TAG, "关闭补光失败", t)
            }
            FlashLinkService.stop(app)
        }
    }

    fun testPulse(holdMs: Int = 300) {
        if (!hasCameraPermission()) {
            fail("请先授予相机权限")
            return
        }
        scope.launch { sync.testPulse(holdMs) }
    }

    // ---------------- 旁听模式（不连相机，只听快门声） ----------------

    // 历史累计次数在构造时从磁盘读入；本次运行的 triggerCount 每启动从 0 开始。
    private val _bystander = MutableStateFlow(
        BystanderState(lifetimeTriggers = LifetimeStats.triggers(app))
    )
    val bystanderState: StateFlow<BystanderState> = _bystander

    /**
     * 启动旁听：开麦 + 开灯待命。**不连接相机**。
     *
     * @param mode CONTINUOUS = 一直亮着（推荐，最可靠）；PULSE = 听到快门才亮
     */
    fun startBystander(mode: LightMode = LightMode.CONTINUOUS) {
        if (!hasCameraPermission()) {
            fail("请先授予相机权限（手电筒需要 CAMERA 权限）")
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _bystander.value = _bystander.value.copy(
                message = "需要录音权限才能听快门声，请在系统设置里授予"
            )
            return
        }

        // 旁听模式不建 PTP 会话；如果之前连过相机，先断开，避免相机处于受控状态
        if (_link.value.state != LinkState.IDLE) {
            Logx.i(TAG, "旁听模式启动：断开已有相机连接，避免相机被锁定")
            disconnect()
        }

        sync.setMode(mode)
        sync.updateConfig(sync.state.value.config)

        FlashLinkService.start(app)

        val ok = audio.start(buildAudioListener())
        if (!ok) {
            _bystander.value = _bystander.value.copy(listening = false, message = "麦克风启动失败")
            return
        }

        _audioRunning.value = true
        _bystander.value = _bystander.value.copy(listening = true, message = null)

        scope.launch {
            try {
                sync.arm()
                _bystander.value = _bystander.value.copy(
                    lightArmed = sync.state.value.armed,
                    message = when (mode) {
                        LightMode.CONTINUOUS -> "补光常亮中，按相机快门声会自动计数"
                        LightMode.PULSE -> "待命：听到快门声才点亮"
                    },
                )
            } catch (t: Throwable) {
                _bystander.value = _bystander.value.copy(message = "开启补光失败: ${t.message}")
            }
        }
        Logx.i(TAG, "旁听模式已启动 mode=$mode")
    }

    fun stopBystander() {
        stopAudio()
        _audioRunning.value = false
        scope.launch {
            runCatching { sync.disarm() }
            FlashLinkService.stop(app)
        }
        _bystander.value = _bystander.value.copy(listening = false, lightArmed = false, level = 0f)
        Logx.i(TAG, "旁听模式已停止")
    }

    fun setBystanderSensitivity(v: Float) {
        val s = v.coerceIn(0f, 1f)
        audio.sensitivity = s
        _bystander.value = _bystander.value.copy(sensitivity = s)
        Logx.i(TAG, "灵敏度 = %.2f".format(s))
    }

    /**
     * 灵敏度预设。
     *
     * 每档同时给出「能量门槛」和「频谱门槛」：
     *  - 灵敏：几乎只看能量，频谱判据放到最松（宁可误触也要抓到）
     *  - 适中：默认，频谱判据正常生效（推荐日常使用）
     *  - 保守：频谱判据严格，只认"典型快门形状"（人声/音乐基本进不来）
     */
    enum class SensPreset(
        val label: String,
        val sens: Float,
        val floor: Float,
        val lowRatio: Float,
        val flatGate: Float,
        val highGate: Float,
        val lowGate: Float,
        val centroidGate: Float,
    ) {
        HIGH("灵敏", 0.98f, 0.0008f, 0.99f, 0.06f, 0.08f, 0.95f, 400f),
        MID("适中", 0.80f, 0.0040f, 0.90f, 0.18f, 0.28f, 0.55f, 1200f),
        LOW("保守", 0.45f, 0.0200f, 0.65f, 0.30f, 0.40f, 0.40f, 1800f),
    }

    fun applySensPreset(preset: SensPreset) {
        audio.applyTuning(preset.sens, preset.floor, preset.lowRatio)
        audio.applySpectrumTuning(preset.flatGate, preset.highGate, preset.lowGate, preset.centroidGate)
        _bystander.value = _bystander.value.copy(
            sensitivity = preset.sens,
            message = when (preset) {
                SensPreset.HIGH -> "「灵敏」：几乎不筛频谱，容易误触但不会漏"
                SensPreset.MID -> "「适中」：按频谱形状筛选，推荐日常使用"
                SensPreset.LOW -> "「保守」：只认典型快门形状，人声/音乐基本进不来"
            },
        )
    }

    /** 手动测一次：让用户确认 LED 与触发链路是通的。 */
    fun bystanderTestPulse() {
        scope.launch {
            runCatching { sync.testPulse(300) }
        }
    }

    fun clearBystanderMessage() {
        _bystander.value = _bystander.value.copy(message = null)
    }

    // ---------------- 频谱采样（校准快门识别） ----------------

    /**
     * 开始/停止频谱采样。
     *
     * 用途：用户按下快门、说一句话，各采一条，然后把导出的数字发回来，
     * 我们就能把判据阈值精确校准到这台相机 + 这个环境的真实特征上。
     *
     * 注意：采样期间**不要求**通过频谱判据，否则会被待校准的判据本身挡掉。
     */
    fun setSpectrumSampling(on: Boolean) {
        Logx.i(TAG, "setSpectrumSampling($on) 进入: listening=${_bystander.value.listening}, " +
            "audioRunning=${_audioRunning.value}, phase=${audio.samplePhase}")
        // 关键：采样必须建立在"录音循环正在跑"之上。
        // 如果用户没先点「开始（常亮）」，录音根本没启动，
        // 这里置了 samplingActive 也永远采不到样本，UI 还显示"采样已开启" —— 静默失效。
        // 所以采样开启时如果没在听，自动拉起监听（只听不亮灯，不动补光）。
        if (on && !_bystander.value.listening) {
            val started = startListeningOnly()
            Logx.i(TAG, "startListeningOnly 结果=$started")
            if (!started) {
                _bystander.value = _bystander.value.copy(
                    sampling = false,
                    samplePhase = "麦克风启动失败",
                    message = "采样失败：麦克风没启动。请检查录音权限后重试。",
                )
                return
            }
        }
        // 确认录音真的在跑，否则直接给出明确错误，不要假装"正在适应环境"
        if (on && !audio.isRunning()) {
            Logx.e(TAG, "录音未运行，采样无法进行")
            _bystander.value = _bystander.value.copy(
                sampling = false,
                samplePhase = "麦克风未运行",
                message = "麦克风没有在录音。请点「开始（常亮）」后再采样。",
            )
            return
        }

        audio.setSampling(on)

        _bystander.value = _bystander.value.copy(
            sampling = on,
            sampleCount = audio.sampleCount(),
            samplePhase = if (on) "环境稳定中…" else "已结束",
            recentWaveforms = if (on) emptyList() else _bystander.value.recentWaveforms,
            message = if (on) {
                "正在自动适应环境，稍等一下会提示你按快门"
            } else {
                "采样已结束，点「一键校准」自动定阈值"
            },
        )
        Logx.i(TAG, "采样状态: $on, 样本数=${audio.sampleCount()}, phase=${audio.samplePhase}")
    }

    /**
     * 构建音频监听器（旁听模式与"仅采样"模式共用）。
     *
     * 抽出来是因为两处需要完全一致的行为：
     * 一处点灯、一处只看数据，但电平/样本/波形的上报逻辑必须相同。
     */
    private fun buildAudioListener(): AudioTrigger.Listener = object : AudioTrigger.Listener {
        override fun onTransient(rms: Float, atNanos: Long) {
            sync.onTrigger(TriggerSource.AUDIO)
            val st = _bystander.value
            val lifetime = LifetimeStats.bump(app)
            _bystander.value = st.copy(
                triggerCount = st.triggerCount + 1,
                lastRms = rms,
                lastTriggerAtMillis = System.currentTimeMillis(),
                lifetimeTriggers = lifetime,
            )
        }

        override fun onError(t: Throwable) {
            Logx.e(TAG, "音频触发错误: ${t.javaClass.simpleName}: ${t.message}", t)
            _bystander.value = _bystander.value.copy(
                listening = false,
                sampling = false,
                samplePhase = "麦克风出错",
                message = "麦克风错误: ${t.javaClass.simpleName}: ${t.message}",
            )
        }

        override fun onLevel(rms: Float, threshold: Float, peak: Float, spectrumInfo: String) {
            val prev = _bystander.value
            val cnt = audio.sampleCount()
            // 样本数变化时刷新波形预览（只在有新样本时取，避免每 50ms 都拷数组）
            val waves = if (cnt != prev.sampleCount) {
                audio.recentWaveforms(5)
            } else {
                prev.recentWaveforms
            }
            _bystander.value = prev.copy(
                level = rms,
                threshold = threshold,
                peak = peak,
                noiseFloor = audio.currentNoiseFloor(),
                sampleCount = cnt,
                spectrumInfo = spectrumInfo,
                recentWaveforms = waves,
                denoiseDb = audio.denoiseSuppressionDb(),
                audioSource = audio.activeSourceName,
                sampling = audio.samplingActive,
                timbreTrained = audio.timbre.isTrained,
                timbreGate = audio.timbre.similarityGate,
                sampleTarget = audio.sampleTarget,
                // 只在采样时取频谱列表，避免平时每 50ms 都拷数组
                recentSpectra = if (audio.samplingActive) audio.recentSpectra(3) else prev.recentSpectra,
                liveSpectrum = if (audio.samplingActive) audio.liveSpectrum() else prev.liveSpectrum,
                samplePhase = when (audio.samplePhase) {
                    com.lumenlink.sync.AudioTrigger.SamplePhase.SETTLING ->
                        "环境稳定中… %d%%（电平 %.4f）".format(
                            (audio.settleProgress() * 100).toInt(), rms
                        )
                    com.lumenlink.sync.AudioTrigger.SamplePhase.CAPTURING ->
                        "可以按快门了（已采 $cnt 条）"
                    com.lumenlink.sync.AudioTrigger.SamplePhase.DONE -> "已采够，点「一键校准」"
                    else -> prev.samplePhase
                },
            )
        }

        override fun onSamplePhaseChanged(capturing: Boolean) {
            _bystander.value = _bystander.value.copy(
                sampling = audio.samplingActive,
                samplePhase = when {
                    !audio.samplingActive -> "已结束"
                    capturing -> "可以按快门了"
                    else -> "已采到 ${audio.sampleCount()} 条，继续按快门"
                },
                message = if (capturing) {
                    "环境已稳定，现在按 3 次相机快门即可"
                } else {
                    "已采够，点「一键校准」"
                },
            )
        }
    }

    /**
     * 只启动麦克风监听，不点亮补光。
     *
     * 用于"只采样/只测试"的场景：用户还没打算开灯，只是要先看看麦克风收到什么。
     *
     * @return 是否成功启动
     */
    private fun startListeningOnly(): Boolean {
        if (_bystander.value.listening) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _bystander.value = _bystander.value.copy(
                message = "需要录音权限，请先在系统设置里授予",
            )
            return false
        }
        FlashLinkService.start(app)
        val ok = audio.start(buildAudioListener())
        if (!ok) return false
        _audioRunning.value = true
        _bystander.value = _bystander.value.copy(listening = true, message = null)
        Logx.i(TAG, "仅监听模式已启动（不点灯）")
        return true
    }

    /** 开关降噪（低端设备底噪高时开启效果明显）。 */
    fun setDenoise(enabled: Boolean) {
        audio.denoiseEnabled = enabled
        _bystander.value = _bystander.value.copy(denoiseEnabled = enabled)
        Logx.i(TAG, "降噪: $enabled")
    }

    /** 设置降噪强度 0..1。 */
    fun setDenoiseStrength(v: Float) {
        audio.denoiseStrength = v.coerceIn(0f, 1f)
        _bystander.value = _bystander.value.copy(denoiseStrength = audio.denoiseStrength)
    }

    fun clearSpectrumSamples() {
        audio.clearSamples()
        _bystander.value = _bystander.value.copy(sampleCount = 0)
    }

    /** 导出采样数据（多行文本）。 */
    fun exportSpectrumSamples(): String = audio.exportSamples()

    /**
     * 一键校准：用真实采样数据自动算出触发阈值，不需要用户手调。
     *
     * 思路（不猜，全部来自实测数据）：
     *  - 取所有样本 rms 的**中位数**作为参考电平，最大值作为"快门峰值"；
     *  - 阈值 = 中位数 + (最大值 - 中位数) × 0.5，即落在两者中间；
     *  - 同时把噪声底（绝对下限）设为中位数的 60%，避免阈值低于环境底噪。
     *
     * 采样要求：至少 2 条。用户只要按 2~3 次快门即可，不用说话（说话样本会被
     * 中位数拉偏，所以这里用中位数而不是均值，抗离群）。
     */
    /**
     * 一键校准：**训练音色模板**，而不是找一个音量阈值。
     *
     * 为什么改：之前取样本电平的中位数和最大值取中点当阈值，本质是猜音量门槛。
     * 说话、关门、放东西的响度都可能超过快门，但音色完全不同——
     * 只有比对**频谱形状**才能真正区分。
     *
     * 训练流程：
     *  1. 采样期间收集每条声音的 24 带频谱向量（已归一化掉响度）
     *  2. 逐带取中位数得到模板（中位数抗离群）
     *  3. 计算样本与模板的一致性，据此自动定相似度门限
     */
    fun autoCalibrateFromSamples(): String {
        val n = audio.sampleCount()
        if (n < 2) {
            return "样本不足（现有 $n 条），至少需要 2 条。\n" +
                "请先点「开始采样」，然后按 2~3 次相机快门，再点「训练音色」。"
        }

        val report = audio.trainTimbre()
        val t = audio.timbre

        _bystander.value = _bystander.value.copy(
            message = if (t.isTrained) {
                "音色模板已就绪（门限 %.2f）".format(t.similarityGate)
            } else {
                "训练失败，请重新采样"
            },
        )
        _bystander.value = _bystander.value.copy(
            samplePhase = if (t.isTrained) "已训练：识别音色中" else "未训练",
        )
        Logx.i(TAG, report)
        return report
    }

    /**
     * 清除历史累计触发次数。
     *
     * 只清历史总量，不动本次运行的计数。
     */
    fun resetLifetimeTriggers() {
        LifetimeStats.reset(app)
        _bystander.value = _bystander.value.copy(lifetimeTriggers = 0)
        Logx.i(TAG, "历史触发次数已清零")
    }

    /** 设置采样目标条数（取值范围见 SampleRange）。 */
    fun setSampleTarget(n: Int) {
        audio.sampleTarget = n
        _bystander.value = _bystander.value.copy(sampleTarget = audio.sampleTarget)
        Logx.i(TAG, "采样目标条数 = ${audio.sampleTarget}")
    }

    /** 清除音色模板，回到未训练状态。 */
    fun resetTimbreTemplate() {
        audio.resetTimbre()
        _bystander.value = _bystander.value.copy(
            samplePhase = "未训练",
            message = "已清除音色模板",
        )
    }

    // ---------------- 只读快门检测（不接管相机） ----------------

    /**
     * 只读轮询模式：不解锁拍摄、不改参数、不切应用模式。
     *
     * 只调用三个只读 PTP 操作（GetStorageIDs / GetObjectHandles / GetObjectInfo），
     * 发现"对象句柄数量增加"就认为拍了一张。
     *
     * 重要取舍（必须让用户知道）：
     *  · 相机在 USB 连接状态下是否仍可机身操作，取决于机型；
     *    Z6II 连 PC 时通常会进入受控状态，但**本模式不发任何会改变相机状态的操作**，
     *    这是我们能做的最小侵入。
     *  · 检测有延迟：文件写入卡需 200ms~1s，轮询间隔 400ms，
     *    所以端到端大约 0.5~1.5 秒。**不能用于实时同步**，只能事后计数/延后补光。
     */
    data class WatchState(
        val running: Boolean = false,
        val polls: Int = 0,
        val detected: Int = 0,
        val lastFilename: String = "",
        val lastLatencyMs: Long = 0,
        val message: String? = null,
    )

    private val _watch = MutableStateFlow(WatchState())
    val watchState: StateFlow<WatchState> = _watch

    private var watchJob: kotlinx.coroutines.Job? = null
    private var lastSeenHandle: Long = -1L

    @Volatile
    private var watchStartNanos: Long = 0

    fun startReadOnlyWatch(intervalMs: Long = 400L) {
        val cam = camera
        if (cam == null) {
            _watch.value = _watch.value.copy(message = "请先连接相机（USB 或 Wi-Fi）")
            return
        }
        if (_watch.value.running) return

        // 首次进入时把当前最新句柄记为基线，避免把已有照片当成新拍
        scope.launch {
            val baseline = withContext(Dispatchers.IO) {
                runCatching { cam.latestObject() }.getOrNull()
            }
            lastSeenHandle = baseline?.handle ?: -1L
            Logx.i(TAG, "只读监视基线句柄=${lastSeenHandle} 文件=${baseline?.filename ?: "无"}")
            _watch.value = _watch.value.copy(
                running = true,
                polls = 0,
                detected = 0,
                lastFilename = baseline?.filename ?: "",
                message = if (baseline == null) {
                    "基线为空（卡里可能没有照片）。现在按快门试试。"
                } else {
                    "基线：${baseline.filename}。现在按相机快门，我会检测新文件。"
                },
            )
            watchStartNanos = System.nanoTime()

            watchJob = scope.launch {
                while (_watch.value.running) {
                    delay(intervalMs)
                    val st = _watch.value
                    if (!st.running) break
                    val obj = withContext(Dispatchers.IO) {
                        runCatching { cam.latestObject() }.getOrNull()
                    }
                    val polls = st.polls + 1
                    if (obj != null && obj.handle > lastSeenHandle) {
                        lastSeenHandle = obj.handle
                        val latency = if (watchStartNanos > 0) {
                            (System.nanoTime() - watchStartNanos) / 1_000_000L
                        } else 0L
                        Logx.i(TAG, "只读检测到新照片: ${obj.filename} handle=${obj.handle}")
                        _watch.value = _watch.value.copy(
                            polls = polls,
                            detected = st.detected + 1,
                            lastFilename = obj.filename,
                            lastLatencyMs = latency,
                            message = "检测到新照片：${obj.filename}",
                        )
                        // 交给同步引擎：常亮模式下只是计数，脉冲模式下点亮
                        sync.onTrigger(TriggerSource.CAMERA_PTP_EVENT)
                    } else {
                        _watch.value = _watch.value.copy(polls = polls)
                    }
                }
            }
        }
    }

    fun stopReadOnlyWatch() {
        watchJob?.cancel()
        watchJob = null
        _watch.value = _watch.value.copy(running = false)
        Logx.i(TAG, "只读监视已停止")
    }

    // ---------------- 蓝牙探测 ----------------

    fun hasBlePermission(): Boolean = ble.hasPermission()

    fun startBleScan() = ble.startScan()

    fun stopBleScan() = ble.stopScan()

    fun connectBle(address: String) = ble.connect(address)

    /** 发起蓝牙配对（Z6II 的私有服务需要加密链路才能读）。pin 为空则用相机下发的。 */
    fun startBleBond(address: String, pin: String? = null) = ble.startBond(address, pin)

    // ---------------- 尼康 BLE 配对（WiFi 的前置条件） ----------------

    /**
     * 执行尼康智能设备 BLE 配对。
     *
     * **这是 WiFi 遥控能用起来的前提。**
     * 实测：未配对时相机只开热点不开放任何 TCP 端口（1-10000 全关）。
     * 走完这 4 阶段握手后，相机才对外提供 PTP/IP。
     */
    fun startNikonPairing(address: String) {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _link.value = _link.value.copy(message = "蓝牙未开启")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            _link.value = _link.value.copy(message = "无法获取设备 $address")
            return
        }
        blePairing.start(device)
    }

    fun stopNikonPairing() = blePairing.close()

    /** 导出配对日志。 */
    fun exportPairingLog(): String {
        val s = blePairing.state.value
        val sb = StringBuilder()
        sb.appendLine("=== 尼康 BLE 配对 ===")
        sb.appendLine("步骤: ${s.step}")
        sb.appendLine("成功: ${s.success}  序列号: ${s.serial.ifBlank { "-" }}")
        s.message?.let { sb.appendLine("提示: $it") }
        sb.appendLine()
        s.log.forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun disconnectBle() = ble.disconnect()

    fun exportBleReport(): String = ble.exportReport()

    /** 网络环境自检：列出 Wi-Fi 网络、网关、候选地址。 */
    fun networkSelfCheck(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 网络自检 ===")
        val ifaces = com.lumenlink.net.CameraDiscovery.localAddresses()
        sb.appendLine("本机 IPv4 地址（${ifaces.size} 个）:")
        if (ifaces.isEmpty()) sb.appendLine("  (无)") else ifaces.forEach {
            sb.appendLine("  ${it.name}  ${it.ipv4}  ${if (it.isWifi) "[Wi-Fi]" else ""}")
        }

        val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
        sb.appendLine()
        sb.appendLine("Wi-Fi 网络（${routes.size} 个，都会用于绑定 socket）:")
        if (routes.isEmpty()) sb.appendLine("  (无)") else routes.forEach {
            sb.appendLine("  ${it.describe()}")
        }

        val hosts = com.lumenlink.net.CameraDiscovery.candidateHosts(app)
        sb.appendLine()
        sb.appendLine("候选相机地址（${hosts.size} 个，按尝试顺序）:")
        hosts.forEach { h ->
            val plane = com.lumenlink.net.CameraDiscovery.networkFor(listOf(h), routes)
            sb.appendLine("  $h   → 走 ${plane?.localIp ?: "默认网络"}")
        }

        sb.appendLine()
        sb.appendLine("注意：这里**不再做裸 TCP 端口探测**。")
        sb.appendLine("原因：连上 15740 就断开会被相机判为异常客户端，")
        sb.appendLine("      严重时相机直接退出 Wi-Fi 模式（显示「无法连接」后关闭）。")
        sb.appendLine("请直接点「自动搜索并连接」，那会对每个候选地址做完整握手。")
        return sb.toString()
    }

    /**
     * TCP 连通性诊断：只连 15740 一次然后断开，**不建 PTP 会话**。
     *
     * 目的是把两类故障分开：
     *  - 全部 refused/超时 → 手机侧根本没发出去（权限/网络绑定/热点没连上）
     *  - 有地址连上 → 相机在监听，问题在 PTP 层（配对/GUID/模式）
     */
    fun tcpProbe(): String {
        val sb = StringBuilder()
        sb.appendLine("=== TCP 连通性 / 端口扫描 ===")
        val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
        routes.forEach { sb.appendLine("Wi-Fi: ${it.describe()}") }
        val plane = cameraNetwork
        sb.appendLine("专属网络: ${plane ?: "(无)"}")
        sb.appendLine()

        val hosts = com.lumenlink.net.CameraDiscovery.candidateHosts(app)
            .ifEmpty { fallbackHosts() }
        val ports = com.lumenlink.net.CameraDiscovery.CANDIDATE_PORTS

        sb.appendLine("扫描端口: ${ports.joinToString()}")
        sb.appendLine()
        val openList = ArrayList<String>()
        hosts.forEach { h ->
            sb.appendLine("$h:")
            val hits = com.lumenlink.net.CameraDiscovery.scanPorts(h, plane)
            hits.forEach { hit ->
                sb.appendLine("  ${hit.port} → ${if (hit.open) "✓ 开放" else "✗ ${hit.outcome}"}")
                if (hit.open) openList.add("${hit.host}:${hit.port}")
            }
        }

        sb.appendLine()
        if (openList.isEmpty()) {
            sb.appendLine("【结论】所有候选端口都没开放。")
            sb.appendLine("→ 相机没在提供 PTP/IP 服务。请检查相机是否真的进入了")
            sb.appendLine("  「连接到智能设备」模式，且屏幕停在等待连接的界面。")
        } else {
            sb.appendLine("【结论】发现开放端口: ${openList.joinToString()}")
            sb.appendLine("→ 网络已通，点「自动搜索并连接」即可。")
        }
        return sb.toString()
    }

    /**
     * 深度扫描 1-10000 端口（并发 64 路，约 30~60 秒）。
     *
     * 这是最后的手段：如果相机在 1-10000 里开了任何 TCP 端口，这里一定能找出来。
     * 一个都找不到 → 相机确实没有提供任何 TCP 服务。
     */
    fun deepPortScan(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 深度端口扫描 1-10000 ===")
        val plane = cameraNetwork
        if (plane == null) {
            sb.appendLine("⚠ 未绑定专属网络，请先点「连接相机热点（应用内）」")
            return sb.toString()
        }
        sb.appendLine("专属网络: $plane")
        val hosts = com.lumenlink.net.CameraDiscovery.candidateHosts(app)
            .ifEmpty { fallbackHosts() }
        val host = hosts.first()
        sb.appendLine("目标: $host")
        sb.appendLine("扫描中，请等待 30~60 秒…")
        Logx.i(TAG, "开始深度扫描 $host")
        val open = com.lumenlink.net.CameraDiscovery.scanRangeConcurrent(
            host = host,
            network = plane,
            from = 1,
            to = 10000,
        )
        sb.appendLine()
        if (open.isEmpty()) {
            sb.appendLine("【结论】1-10000 端口全部关闭。")
            sb.appendLine("→ 相机在线（能回 RST）但没有任何 TCP 服务在监听。")
            sb.appendLine("→ 这不可能是端口号猜错导致的。")
        } else {
            sb.appendLine("【结论】发现 ${open.size} 个开放端口:")
            sb.appendLine(open.joinToString(", "))
        }
        return sb.toString()
    }

    /** mDNS 查询 _ptp._tcp：看相机是否真的在广播 PTP 服务。 */
    fun mdnsReport(): String {
        val sb = StringBuilder()
        sb.appendLine(com.lumenlink.net.CameraDiscovery.mdnsQueryPtp(cameraNetwork, 3000))
        return sb.toString()
    }

    /** 全端口快扫：看清相机到底开了哪些服务。 */
    fun fullPortScan(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 全端口快扫 ===")
        val plane = cameraNetwork
        sb.appendLine("专属网络: ${plane ?: "(无)"}")
        val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
        routes.forEach { sb.appendLine("Wi-Fi: ${it.describe()}") }
        val hosts = com.lumenlink.net.CameraDiscovery.candidateHosts(app)
            .ifEmpty { fallbackHosts() }
        sb.appendLine()
        sb.appendLine("目标: ${hosts.take(2).joinToString()}")
        sb.appendLine()

        var anyOpen = false
        for (h in hosts.take(2)) {
            sb.appendLine("--- $h ---")
            val hits = com.lumenlink.net.CameraDiscovery.scanPortRange(h, plane)
            val open = hits.filter { it.open }
            if (open.isEmpty()) {
                sb.appendLine("  没有任何开放端口")
            } else {
                open.forEach { sb.appendLine("  ★ ${it.port} 开放") }
                anyOpen = true
            }
        }

        sb.appendLine()
        if (!anyOpen) {
            sb.appendLine("【结论】相机连一个 TCP 端口都没开。")
            sb.appendLine("→ 它只是被 Wi-Fi 关联上了，没有启动任何服务。")
            sb.appendLine("→ 请确认相机屏幕停在「显示 SSID 和密码」的界面，")
            sb.appendLine("  而不是仅仅 Wi-Fi 开关打开。")
        } else {
            sb.appendLine("【结论】相机有服务在跑，把上面开放的端口号发我。")
        }
        return sb.toString()
    }

    /** UDP 广播发现（最安全，不建任何 TCP 连接）。 */
    fun udpDiscoverReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== UDP 广播发现 ===")
        val plane = cameraNetwork
        sb.appendLine("专属网络: ${plane ?: "(无，建议先点「连接相机热点（应用内）」)"}")
        val found = com.lumenlink.net.CameraDiscovery.udpDiscover(plane, 2500)
        sb.appendLine()
        if (found.isEmpty()) {
            sb.appendLine("没有收到任何 UDP 应答。")
            sb.appendLine("（这很正常：很多相机不广播 mDNS，不等于网络不通）")
        } else {
            sb.appendLine("收到应答的主机:")
            found.forEach { sb.appendLine("  $it") }
        }
        return sb.toString()
    }

    // ---------------- 分步连相机（验证"哪一步开始锁机"） ----------------

    /**
     * 逐步建立 PTP 连接，每步之后由用户手动测试相机是否仍可拍摄。
     *
     * 为什么需要：USB 已确认锁机，蓝牙不锁但读不到数据。
     * Wi-Fi 是否锁机取决于**哪一步**触发相机的受控状态 ——
     * 可能是 OpenSession，也可能握手阶段就会。
     * 只有分步验证才能定位，进而判断"停在锁机之前的那一步"是否还有可用信息。
     */
    data class StagedState(
        val step: Int = 0,
        val log: List<String> = emptyList(),
        val message: String? = null,
    )

    private val _staged = MutableStateFlow(StagedState())
    val stagedState: StateFlow<StagedState> = _staged

    private var stagedTransport: PtpIpTransport? = null

    private fun stagedAppend(line: String) {
        Logx.i(TAG, "[分步] $line")
        _staged.value = _staged.value.copy(log = (_staged.value.log + line).takeLast(40))
    }

    /** 步骤1：TCP 连接 + InitCommandRequest 握手（不发 OpenSession）。 */
    fun stagedStep1(host: String) {
        scope.launch {
            try {
                stagedTransport?.close()
                val routes = com.lumenlink.net.CameraDiscovery.wifiRoutes(app)
                val route = com.lumenlink.net.CameraDiscovery.networkFor(listOf(host), routes)
                stagedAppend("出口网络: ${route?.describe() ?: "(默认网络)"}")
                val t = PtpIpTransport(
                    host = host,
                    port = PtpIpTransport.DEFAULT_PORT,
                    network = route?.network,
                )
                stagedTransport = t
                withContext(Dispatchers.IO) { t.connectStage1(8000) }
                stagedAppend("✓ 步骤1 完成：TCP + 握手成功")
                stagedAppend("  → 现在请按相机机身快门，看能否拍照")
                _staged.value = _staged.value.copy(
                    step = 1,
                    message = "步骤1完成。请按机身快门测试——如果能拍，说明握手本身不锁机。",
                )
            } catch (t: Throwable) {
                stagedAppend("✗ 步骤1 失败: ${t.message}")
                _staged.value = _staged.value.copy(message = "步骤1失败: ${t.message}")
            }
        }
    }

    /** 步骤2：建立事件通道。 */
    fun stagedStep2() {
        val t = stagedTransport ?: run {
            _staged.value = _staged.value.copy(message = "请先完成步骤1")
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { t.connectStage2(8000) }
                stagedAppend("✓ 步骤2 完成：事件通道 = ${if (t.pushEventsActive) "已建立" else "被拒绝"}")
                stagedAppend("  → 再按一次机身快门，看能否拍照")
                _staged.value = _staged.value.copy(
                    step = 2,
                    message = "步骤2完成。再按机身快门测试。",
                )
            } catch (t2: Throwable) {
                stagedAppend("✗ 步骤2 失败: ${t2.message}")
            }
        }
    }

    /** 步骤3：发 OpenSession（这一步通常是"接管"的开始）。 */
    fun stagedStep3() {
        val t = stagedTransport ?: return
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    t.request(com.lumenlink.ptp.Ptp.OC_OpenSession, intArrayOf(1), null, 8000)
                }
                stagedAppend("OpenSession -> ${com.lumenlink.ptp.Ptp.responseName(resp.code)}")
                stagedAppend("✓ 步骤3 完成")
                stagedAppend("  → 再按机身快门，这一步最可能开始锁机")
                _staged.value = _staged.value.copy(
                    step = 3,
                    message = "步骤3完成。再按机身快门——如果这时拍不了了，说明 OpenSession 是锁机的那一步。",
                )
            } catch (e: Throwable) {
                stagedAppend("✗ 步骤3 失败: ${e.message}")
            }
        }
    }

    /** 步骤4：读一个只读属性，验证数据通路。 */
    fun stagedStep4() {
        val t = stagedTransport ?: return
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    t.request(com.lumenlink.ptp.Ptp.OC_GetDevicePropDesc, intArrayOf(0x5001), null, 8000)
                }
                stagedAppend("GetDevicePropDesc(0x5001 电池) -> ${com.lumenlink.ptp.Ptp.responseName(resp.code)}" +
                    " data=${resp.data?.size ?: 0}B")
                _staged.value = _staged.value.copy(
                    step = 4,
                    message = if (resp.ok) "步骤4完成：数据通路可用（能读到属性）" else "步骤4完成，但读属性失败",
                )
            } catch (e: Throwable) {
                stagedAppend("✗ 步骤4 失败: ${e.message}")
            }
        }
    }

    fun stagedReset() {
        scope.launch {
            runCatching { stagedTransport?.close() }
            stagedTransport = null
            _staged.value = StagedState()
        }
    }

    /** 主动读取所有可读特征（看哪个通道承载状态）。 */
    fun readAllBleChars() = ble.readAllReadable()

    /** 实验性：向指定特征写探测值。 */
    fun bleWriteProbe(shortName: String, hex: String): String = ble.writeProbe(shortName, hex)

    /**
     * 麦克风自检：告诉用户"现在到底听到了多响"。
     *
     * 这一步很关键：如果底噪量级本身就 0.004 以上，说明麦克风收音极差
     * （被遮挡/增益过低/设备问题），那么无论怎么调阈值都不可能可靠识别快门。
     */
    fun micSelfCheck(): String {
        val st = _bystander.value
        if (!st.listening) {
            return "麦克风未启动。请先点「开始（常亮）」再自检。"
        }
        val snr = if (st.noiseFloor > 1e-9f) st.level / st.noiseFloor else 0f
        val sb = StringBuilder()
        sb.appendLine("=== 麦克风自检 ===")
        sb.appendLine("录音源       : ${audio.activeSourceName.ifBlank { "(未启动)" }}")
        sb.appendLine("当前电平 RMS : %.5f".format(st.level))
        sb.appendLine("噪声底       : %.5f".format(st.noiseFloor))
        sb.appendLine("当前/噪声底  : %.1f 倍".format(snr))
        sb.appendLine("上升沿阈值   : %.1f 倍".format(audio.currentRiseGate()))
        sb.appendLine("采样状态     : " + if (st.sampling) "采集中（已 ${st.sampleCount} 条）" else "未开启")
        sb.appendLine("频谱摘要     : ${st.spectrumInfo}")
        sb.appendLine()
        sb.appendLine("判读：")
        sb.appendLine("· 静止时「当前电平」应在 0.001~0.01 之间；若长期 >0.02，环境太吵或麦克风增益异常")
        sb.appendLine("· 按快门瞬间，该值应出现 3 倍以上的跳变；若毫无变化，说明麦克风收不到快门声")
        sb.appendLine("· 手机麦克风一般在底部，别被壳或手挡住；距相机建议 30cm 内")
        sb.appendLine("· 录音源是 MIC 而非 UNPROCESSED/VOICE_RECOGNITION 时，系统可能加了降噪，会削掉瞬态")
        return sb.toString()
    }

    // ---------------- 音频触发 ----------------

    fun startAudio() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("缺少录音权限，无法使用音频触发")
            return
        }
        val ok = audio.start(object : AudioTrigger.Listener {
            override fun onTransient(rms: Float, atNanos: Long) {
                sync.onTrigger(TriggerSource.AUDIO)
            }

            override fun onError(t: Throwable) {
                Logx.e(TAG, "音频触发错误", t)
                _audioRunning.value = false
            }

            override fun onLevel(rms: Float, threshold: Float, peak: Float, spectrumInfo: String) {
                _audioLevel.value = rms
            }
        })
        _audioRunning.value = ok
        if (ok && _link.value.state != LinkState.ERROR) {
            _link.value = _link.value.copy(message = "音频触发已开启：按快门即可联动")
        }
    }

    fun stopAudio() {
        audio.stop()
        _audioRunning.value = false
        _audioLevel.value = 0f
    }

    fun setSensitivity(v: Float) {
        audio.sensitivity = v.coerceIn(0f, 1f)
    }

    // ---------------- 相机参数 ----------------

    fun writeShutter(value: String) {
        val cam = camera ?: run { fail("相机未连接"); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { cam.writeShutter(value) }
            if (ok) {
                _link.value = _link.value.copy(shutter = value, message = "快门速度已设为 $value")
                // 让补光窗口跟着快门走 —— 这是本 App 的核心联动
                applyShutterToSync(value)
            } else {
                _link.value = _link.value.copy(message = "设置快门速度失败（该机型可能不支持或值不在候选表内）")
            }
        }
    }

    fun writeIso(value: String) {
        val cam = camera ?: run { fail("相机未连接"); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { cam.writeIso(value) }
            _link.value = _link.value.copy(
                iso = if (ok) value else _link.value.iso,
                message = if (ok) "ISO 已设为 $value" else "设置 ISO 失败",
            )
        }
    }

    fun triggerCapture() {
        val cam = camera ?: run { fail("相机未连接"); return }
        scope.launch {
            var ok = withContext(Dispatchers.IO) { cam.capture() }
            var note = ""
            if (!ok) {
                // 常规路径全败：再试一次「先切遥控模式再释放」。
                // Z6II 在 USB 连接状态下有时需要先进应用模式才响应释放。
                ok = withContext(Dispatchers.IO) { cam.captureWithModeSwitch() }
                note = if (ok) "（切换到遥控模式后成功）" else "（已尝试全部释放路径）"
            }
            val opName = cam.lastCaptureOpcode?.let { "0x%04X".format(it) } ?: "无"
            _link.value = _link.value.copy(
                message = if (ok) {
                    "拍摄指令已下发 $note，使用操作码 $opName"
                } else {
                    "拍摄指令失败 $note。Z6II 的 capability 是「No Open Capture」，" +
                        "只认尼康私有释放；具体哪个 opcode 可用见诊断里的日志。"
                }
            )
        }
    }

    /** 把 "1/250" 这样的字符串换成毫秒，喂给同步引擎计算点亮窗口。 */
    private fun applyShutterToSync(value: String) {
        val ms = com.lumenlink.nikon.NikonMath.shutterTextToMillis(value)
        if (ms > 0) {
            val cfg = sync.state.value.config.copy(shutterMillis = ms.coerceIn(1, 60_000))
            sync.updateConfig(cfg)
            Logx.i(TAG, "同步窗口更新: 快门 $value -> ${ms}ms, LED 点亮 ${cfg.holdMillis()}ms")
        }
    }

    fun clearMessage() {
        _link.value = _link.value.copy(message = null)
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 生成诊断文本。
     *
     * 这个方法会被 UI 直接在点击回调里调用，**任何异常都会表现为"点复制就闪退"**，
     * 所以整段包在 try/catch 里：宁可返回一份残缺的诊断，也绝不能让 App 崩掉。
     * 同时每个子项单独兜住，避免"一处失败导致整份诊断丢失"。
     */
    fun diagnostics(): String {
        val sb = StringBuilder()
        fun section(name: String, block: () -> String?) {
            sb.appendLine("--- $name ---")
            sb.appendLine(
                try {
                    block() ?: "(无)"
                } catch (t: Throwable) {
                    "(生成失败: ${t.javaClass.simpleName}: ${t.message})"
                }
            )
        }

        try {
            sb.appendLine("=== LumenLink 诊断 v" + com.lumenlink.BuildConfig.VERSION_NAME +
                " (code " + com.lumenlink.BuildConfig.VERSION_CODE + ") ===")
        } catch (_: Throwable) {
            sb.appendLine("=== LumenLink 诊断 ===")
        }

        section("设备") {
            "Xiaomi/Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        section("连接") {
            "${_link.value.state} ${_link.value.transport} ${_link.value.detail}"
        }
        section("机型与属性") {
            "机型=${_link.value.model}  属性数=${_link.value.props}  快门可写=${_link.value.shutterWritable}"
        }
        section("闪光灯") { flash.state.value.toString() }
        section("同步") { sync.state.value.toString() }
        section("PTP 属性表") { camera?.propTableDump() }
        section("USB 端点列表") { dumpUsbEndpoints() }
        section("本次 USB 绑定") { lastUsbTransport?.endpointSummary() }
        section("遥控释放路径探测") { camera?.probeRemoteOps() }
        section("Ex 属性通道探测") { camera?.probeExProps() }
        section("客户端身份") {
            "friendlyName=" + com.lumenlink.core.ClientIdentity.friendlyName(app) +
                "\nguid=" + com.lumenlink.core.ClientIdentity.currentGuidText(app)
        }
        section("上次崩溃记录") { com.lumenlink.core.CrashLog.readLast(app)?.take(6000) }
        section("初始化状态") { initFailure ?: "正常" }
        section("日志") { com.lumenlink.core.Logx.dump() }
        return sb.toString()
    }

    companion object {
        private const val TAG = "Link"

        /**
         * 初始化失败时的降级实例。
         * 目的：即使 LinkController 构造过程中某个组件炸了，App 也要能启动，
         * 让用户至少能把崩溃信息导出来，而不是黑屏/闪退。
         */
        fun createFallback(app: App, cause: Throwable): LinkController {
            Logx.e(TAG, "降级初始化", cause)
            return try {
                LinkController(app, skipInit = true).also { it.initFailure = cause.message }
            } catch (t: Throwable) {
                Logx.e(TAG, "降级初始化也失败: ${t.message}", t)
                throw t
            }
        }
    }
}
