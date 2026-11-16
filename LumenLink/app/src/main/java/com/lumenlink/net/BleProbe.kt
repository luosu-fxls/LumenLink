package com.lumenlink.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lumenlink.core.Logx
import java.util.UUID

/**
 * 蓝牙探测（BLE）。
 *
 * ⚠️ 先说清目的与边界：
 *
 * 尼康的蓝牙是**私有协议**，配对、鉴权、指令格式都没有公开规范。
 * 官方 SnapBridge 与相机之间有一套协商流程，第三方无法可靠复现。
 * 所以本类**不做**"连上相机并读事件"这种承诺，只做三件事：
 *
 *  1. 扫描附近的 BLE 设备，识别哪些像尼康相机；
 *  2. 连接后**枚举全部 GATT 服务/特征**（含 UUID、属性、通知开关）；
 *  3. 对可通知（NOTIFY/INDICATE）的特征打开订阅并打印收到的原始字节。
 *
 * 这三步的意义：如果相机的快门事件是通过某个可通知特征推送的，
 * 打开订阅后**不需要鉴权也能看到有数据流动**——这本身就是决定性信息。
 * 反之，如果所有特征都需要鉴权才能读写，那这条路对第三方就是封死的，
 * 我们会得到明确结论，而不是一直猜。
 *
 * 不锁快门：BLE 连接不会让相机进入 PTP 遥控模式，机身操作不受影响。
 */
private const val BluetoothDevice_VARIANT_CONSENT = 2

class BleProbe(private val context: Context) {

    data class BleDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val serviceUuids: List<String>,
        /** 广播里带尼康私有服务 0000de00 → 基本可以确定是这台相机 */
        val looksLikeNikon: Boolean = false,
    )

    data class NotifyRecord(
        val uuid: String,
        val atMillis: Long,
        val hex: String,
    )

    data class ProbeState(
        val scanning: Boolean = false,
        val devices: List<BleDevice> = emptyList(),
        val connectedAddress: String = "",
        val services: List<String> = emptyList(),
        val notifyEnabled: List<String> = emptyList(),
        val notifications: List<NotifyRecord> = emptyList(),
        /** 对每个特征主动读取的结果，用于判断哪个通道有数据。 */
        val reads: List<String> = emptyList(),
        val status: String = "",
        val message: String? = null,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ProbeState())
    val state: kotlinx.coroutines.flow.StateFlow<ProbeState> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothOn(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    // ---------------- 扫描 ----------------

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 12_000L) {
        if (!hasPermission()) {
            _state.value = _state.value.copy(message = "缺少蓝牙权限，请先授予")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(message = "蓝牙未开启，请先在系统设置里打开")
            return
        }
        if (_state.value.scanning) return

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = _state.value.copy(message = "无法获取 BLE 扫描器")
            return
        }

        _state.value = _state.value.copy(scanning = true, devices = emptyList(), message = null)
        Logx.i(TAG, "开始 BLE 扫描 ${durationMs}ms")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 不设过滤器：先把附近所有设备列出来，让用户判断哪个是相机
        scanner?.startScan(null, settings, scanCallback)

        handler.postDelayed({
            stopScan()
            val n = _state.value.devices.size
            _state.value = _state.value.copy(
                scanning = false,
                message = if (n == 0) "没扫到任何 BLE 设备。请确认相机蓝牙已开启（相机：网络菜单 → 连接到智能设备 → 蓝牙连接）"
                else "扫到 $n 个设备。找到相机（名字通常含 NIKON 或 Z 6），点它连接。"
            )
        }, durationMs)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
            // 过滤掉无名字的设备，减少噪音（但保留有服务 UUID 的）
            if (name.isBlank() && uuids.isEmpty()) return
            val looksLikeNikon = uuids.any { it.startsWith("0000de00", true) }
            val dev = BleDevice(
                name = name.ifBlank { "(无名)" },
                address = result.device.address,
                rssi = result.rssi,
                serviceUuids = uuids,
                looksLikeNikon = looksLikeNikon,
            )
            val cur = _state.value
            val list = cur.devices.toMutableList()
            val idx = list.indexOfFirst { it.address == dev.address }
            if (idx >= 0) list[idx] = dev else list.add(dev)
            _state.value = cur.copy(devices = list.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            Logx.w(TAG, "BLE 扫描失败 code=$errorCode")
            _state.value = _state.value.copy(
                scanning = false,
                message = "扫描失败，错误码 $errorCode（1=太频繁 2=权限 3=内部错误）",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
        scanner = null
        _state.value = _state.value.copy(scanning = false)
    }

    // ---------------- 连接并枚举服务 ----------------

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermission()) {
            _state.value = _state.value.copy(message = "缺少蓝牙权限")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(message = "蓝牙未开启")
            return
        }
        disconnect()

        // ⚠️ BLE 地址可能是**随机化**的（iOS / 部分安卓每次连接都变），
        // 所以不能只用地址当身份。这里优先访问已知设备列表里同名的那台。
        var device = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            null
        }
        if (device == null) {
            // 回退：在已配对/已绑定设备里找
            device = try {
                adapter.bondedDevices?.firstOrNull { it.address == address }
            } catch (_: Throwable) {
                null
            }
        }
        if (device == null) {
            _state.value = _state.value.copy(
                message = "无法获取设备 $address —— BLE 地址可能已随机化，请重新扫描后再点。",
            )
            return
        }

        Logx.i(TAG, "连接 BLE 设备 ${device.address} (${device.name ?: "?"})")
        _state.value = _state.value.copy(
            connectedAddress = device.address,
            services = emptyList(),
            notifyEnabled = emptyList(),
            notifications = emptyList(),
            reads = emptyList(),
            status = "正在连接 ${device.address}…",
            message = null,
        )
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice_TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Logx.i(TAG, "BLE 已连接，开始发现服务")
                _state.value = _state.value.copy(status = "已连接，正在发现服务…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logx.i(TAG, "BLE 已断开 status=$status")
                _state.value = _state.value.copy(status = "已断开 (status=$status)")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(
                    status = "服务发现失败 status=$status",
                    message = "服务发现失败。部分设备需要先配对才能读出服务。",
                )
                return
            }

            val lines = ArrayList<String>()
            val notifiable = ArrayList<String>()
            for (svc in g.services) {
                lines.add("SERVICE ${svc.uuid}  (${describeUuid(svc.uuid)})")
                for (ch in svc.characteristics) {
                    val props = describeProps(ch)
                    lines.add("    CHAR ${ch.uuid}  [$props]  (${describeUuid(ch.uuid)})")
                    val canNotify = (ch.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                    if (canNotify) {
                        notifiable.add(ch.uuid.toString())
                        // 打开本地通知开关 + 写 CCCD
                        try {
                            g.setCharacteristicNotification(ch, true)
                            val cccd = ch.getDescriptor(CCCD_UUID)
                            if (cccd != null) {
                                writeCccd(g, cccd, ch)
                            }
                        } catch (t: Throwable) {
                            Logx.w(TAG, "订阅 ${ch.uuid} 失败: ${t.message}")
                        }
                    }
                }
            }
            // 连接真实性校验：读 Device Information 的厂商/型号特征。
            // 只有读到内容才算"真的连上了这台相机"，而不是连了个随机地址的幽灵。
            val identity = readDeviceIdentity(g)
            Logx.i(TAG, "设备身份: $identity")
            Logx.i(TAG, "服务枚举完成: ${g.services.size} 个服务, ${notifiable.size} 个可通知特征")
            _state.value = _state.value.copy(
                services = lines,
                notifyEnabled = notifiable,
                status = "枚举完成：${g.services.size} 个服务，${notifiable.size} 个可通知 | 身份: $identity",
                message = if (notifiable.isEmpty()) {
                    "这台设备没有可通知的特征 —— 说明它不主动推送事件，或者相关服务被隐藏（需要厂商鉴权后才可见）。"
                } else {
                    "已订阅通知。现在去按相机快门，观察下面是否出现数据。"
                },
            )
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = ch.value ?: return
            recordNotification(ch.uuid.toString(), data)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            recordNotification(ch.uuid.toString(), value)
        }
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            recordRead(ch.uuid.toString(), value, status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            val value = ch.value ?: ByteArray(0)
            recordRead(ch.uuid.toString(), value, status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val line = "WRITE %s -> status=%d".format(shortUuid(ch.uuid.toString()), status)
            Logx.i(TAG, line)
            _state.value = _state.value.copy(
                reads = (_state.value.reads + line).takeLast(60),
            )
        }
    }

    /**
     * 把 GATT 状态码翻译成人话。
     *
     * 这轮探测的核心目的就是**拿到确切的失败原因**：
     *  - 5  (INSUFFICIENT_AUTHENTICATION) → 需要配对/绑定
     *  - 15 (INSUFFICIENT_ENCRYPTION)     → 需要加密链路（绑定后自动加密）
     *  - 8  (INSUFFICIENT_AUTHORIZATION)  → 应用层另有鉴权（尼康私有握手）
     *  - 133 (GATT_ERROR)                 → 安卓侧通用错误，多为设备要求绑定但未绑定
     *  - 2  (READ_NOT_PERMITTED)          → 该特征本身不可读
     */
    private fun gattStatusHint(status: Int): String = when (status) {
        0 -> "成功"
        2 -> "该特征不允许读（属性未开放）"
        3 -> "该特征不允许写"
        5 -> "需要配对/绑定（INSUFFICIENT_AUTHENTICATION）"
        8 -> "需要应用层鉴权（INSUFFICIENT_AUTHORIZATION）"
        13 -> "数据长度无效"
        15 -> "需要加密链路（INSUFFICIENT_ENCRYPTION）→ 先完成绑定"
        133 -> "GATT 通用错误，通常表示设备要求绑定但链路未加密"
        else -> "状态码 $status"
    }

    // ---------------- 绑定（配对） ----------------

    /**
     * 配对请求广播接收器。
     *
     * 为什么必须注册它：Z6II 的蓝牙配对走的是 **PIN 码**（相机屏幕上会显示数字）。
     * 系统收到配对请求后会弹自己的对话框，但在部分 ROM 上（尤其后台运行时）
     * 这个对话框不会出现，配对直接被拒绝 —— 用户看到的就是
     * 「PIN 码不正确」或连接以 status=22 断开。
     *
     * 注册 PAIRING_REQUEST 后，我们可以在 App 里接管这次配对：
     *  - 有 PIN 时用 setPin 提交；
     *  - 需要确认时用 setPairingConfirmation(true)；
     *  - 需要输入时用 cancelPairingUserInput 让系统弹框。
     */
    private val pairingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action != android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                    android.bluetooth.BluetoothDevice::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            }
            val variant = intent.getIntExtra(
                android.bluetooth.BluetoothDevice.EXTRA_PAIRING_VARIANT,
                -1,
            )
            val pin = intent.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_PAIRING_KEY)
            Logx.i(TAG, "收到配对请求 addr=${device?.address} variant=$variant pin=$pin")
            _state.value = _state.value.copy(
                reads = (_state.value.reads + "PAIR 请求 variant=$variant pin=${pin ?: "-"}").takeLast(60),
            )

            if (device == null) return
            try {
                // 用户预先填了 PIN，就直接提交
                val manual = manualPin
                if (manual != null && variant == android.bluetooth.BluetoothDevice.PAIRING_VARIANT_PIN) {
                    @Suppress("DEPRECATION")
                    device.setPin(manual.toByteArray(Charsets.UTF_8))
                    Logx.i(TAG, "已提交手动 PIN")
                    _state.value = _state.value.copy(
                        reads = (_state.value.reads + "PAIR 已提交手动 PIN").takeLast(60),
                    )
                    abortBroadcast()
                    return
                }

                when (variant) {
                    // 相机直接告诉我们 PIN（通常同时显示在相机屏幕上），自动确认即可
                    android.bluetooth.BluetoothDevice.PAIRING_VARIANT_PIN -> {
                        // 没有手动 PIN：把相机给的 PIN 原样提交
                        if (pin != null) {
                            @Suppress("DEPRECATION")
                            device.setPin(pin.toByteArray(Charsets.UTF_8))
                            Logx.i(TAG, "已用相机下发的 PIN 自动确认")
                        }
                        abortBroadcast()
                    }
                    // Just Works / 数字比较：直接确认
                    // PAIRING_VARIANT_CONSENT 是隐藏常量（@SystemApi），用字面值
                    BluetoothDevice_VARIANT_CONSENT,
                    android.bluetooth.BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                        @Suppress("DEPRECATION")
                        device.setPairingConfirmation(true)
                        Logx.i(TAG, "已自动确认配对")
                        _state.value = _state.value.copy(
                            reads = (_state.value.reads + "PAIR 已自动确认").takeLast(60),
                        )
                        abortBroadcast()
                    }
                    else -> {
                        // 需要用户输入 PIN：交回系统弹框
                        Logx.i(TAG, "variant=$variant 需要系统输入框")
                        _state.value = _state.value.copy(
                            status = "请在系统弹窗中输入相机显示的 PIN",
                        )
                    }
                }
            } catch (t: Throwable) {
                Logx.e(TAG, "处理配对请求失败", t)
            }
        }
    }

    /** 用户手动填写的 PIN（为空则用相机下发的）。 */
    @Volatile
    var manualPin: String? = null

    private var receiverRegistered = false

    private fun registerPairingReceiver() {
        if (receiverRegistered) return
        try {
            val filter = android.content.IntentFilter(
                android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
            )
            filter.priority = Int.MAX_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    pairingReceiver, filter, android.content.Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(pairingReceiver, filter)
            }
            receiverRegistered = true
            Logx.i(TAG, "配对请求接收器已注册")
        } catch (t: Throwable) {
            Logx.w(TAG, "注册配对接收器失败: ${t.message}")
        }
    }

    /**
     * 发起系统绑定流程。
     *
     * 为什么必须做：Z6II 的私有服务 `0000de00-...` 处在**加密保护**之下。
     * 未绑定时能枚举出特征列表（属性是明文广播的），但一读就返回
     * 5 / 15 / 133 —— 这就是之前"两轮探测都读不到数据"的真正原因。
     *
     * 绑定过程中相机会在屏幕上弹确认（部分固件要求按 OK），
     * 所以这里必须给足时间，并且把每一步状态打出来。
     */
    @SuppressLint("MissingPermission")
    fun startBond(address: String, pin: String? = null) {
        if (!hasPermission()) {
            _state.value = _state.value.copy(message = "缺少蓝牙权限")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            _state.value = _state.value.copy(message = "系统版本过低，不支持主动绑定")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(message = "蓝牙未开启")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(message = "无法获取设备 $address")
            return
        }

        val bond = device.bondState
        Logx.i(TAG, "发起绑定 ${device.address}，当前 bondState=$bond pin=${pin ?: "(自动)"}")
        if (bond == android.bluetooth.BluetoothDevice.BOND_BONDED) {
            _state.value = _state.value.copy(
                status = "该设备已配对",
                message = "已配对。如果仍然读不到数据，说明卡在应用层鉴权，请点「配对后重新连接」。",
            )
            return
        }

        // 关键：先注册配对请求接收器，否则 PIN 码请求会被系统直接拒绝
        manualPin = pin?.takeIf { it.isNotBlank() }
        registerPairingReceiver()

        _state.value = _state.value.copy(
            status = "正在配对…（请留意相机屏幕，可能需要在机身上按确认）",
            message = null,
            reads = _state.value.reads + "BOND 发起 createBond()",
        )

        watchBondState(device, adapter)

        val ok = try {
            device.createBond()
        } catch (t: Throwable) {
            Logx.e(TAG, "createBond 抛异常", t)
            false
        }
        if (!ok) {
            _state.value = _state.value.copy(
                status = "createBond 返回 false",
                message = "系统拒绝发起绑定。请到系统设置 → 蓝牙 里手动添加/配对这台相机，" +
                    "或者确认相机处于可配对状态。",
            )
        }
    }

    /**
     * 轮询绑定状态直到成功/失败。
     *
     * 不用 broadcast 是因为 Android 12+ 的 ACTION_BOND_STATE_CHANGED
     * 在某些 ROM 上注册受限；轮询更稳，代价只是 1 秒一次。
     */
    @SuppressLint("MissingPermission")
    private fun watchBondState(device: android.bluetooth.BluetoothDevice, adapter: BluetoothAdapter) {
        val deadline = System.currentTimeMillis() + 60_000L
        var lastState = -1
        val tick = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                val now = try {
                    adapter.getRemoteDevice(device.address).bondState
                } catch (_: Throwable) {
                    -1
                }
                if (now != lastState) {
                    lastState = now
                    val text = when (now) {
                        android.bluetooth.BluetoothDevice.BOND_BONDED -> "BOND_BONDED（已配对）"
                        android.bluetooth.BluetoothDevice.BOND_BONDING -> "BOND_BONDING（配对中…）"
                        android.bluetooth.BluetoothDevice.BOND_NONE -> "BOND_NONE（未配对）"
                        else -> "未知($now)"
                    }
                    Logx.i(TAG, "绑定状态 → $text")
                    _state.value = _state.value.copy(
                        reads = (_state.value.reads + "BOND 状态 → $text").takeLast(60),
                    )
                }
                when (now) {
                    android.bluetooth.BluetoothDevice.BOND_BONDED -> {
                        _state.value = _state.value.copy(
                            status = "✓ 配对成功",
                            message = "配对完成。现在点「退出配对模式并重连」——" +
                                "绑定后需要重建 GATT 连接才会启用加密链路。",
                        )
                    }
                    android.bluetooth.BluetoothDevice.BOND_NONE -> {
                        if (System.currentTimeMillis() > deadline) {
                            _state.value = _state.value.copy(
                                status = "配对超时/被拒",
                                message = "60 秒内没完成配对。常见原因：\n" +
                                    "1) 相机屏幕上出现了确认请求但没按；\n" +
                                    "2) 相机已被别的手机绑定（需在相机上删除旧设备）；\n" +
                                    "3) 相机没进入可配对状态。",
                            )
                            return
                        }
                    }
                }
                if (System.currentTimeMillis() < deadline &&
                    now != android.bluetooth.BluetoothDevice.BOND_BONDED
                ) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.postDelayed(tick, 500L)
    }

    /**
     * 读 Device Information 服务的厂商名(2a29)/型号(2a24)，用于确认真连上了相机。
     *
     * 这一步是"连接真实性"的判据：BLE 地址会随机化，
     * 只有读到相机自报的型号，才能确定这条链路是真的。
     */
    @SuppressLint("MissingPermission")
    private fun readDeviceIdentity(g: BluetoothGatt): String {
        val results = LinkedHashMap<String, String>()
        for (svc in g.services) {
            if (!svc.uuid.toString().lowercase().startsWith("0000180a")) continue
            for (ch in svc.characteristics) {
                val short = shortUuid(ch.uuid.toString())
                if (short != "2a29" && short != "2a24" && short != "2a26") continue
                try {
                    val v = readSync(g, ch)
                    if (v != null) {
                        val text = String(v, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                        results[when (short) {
                            "2a29" -> "厂商"
                            "2a24" -> "型号"
                            else -> "版本"
                        }] = text
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return if (results.isEmpty()) {
            "(读不到，可能需鉴权)"
        } else {
            results.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
    }

    /** 同步读取一个特征（阻塞最多 1.5 秒）。 */
    @SuppressLint("MissingPermission")
    private fun readSync(g: BluetoothGatt, ch: BluetoothGattCharacteristic): ByteArray? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        pendingRead = { data ->
            out = data
            latch.countDown()
        }
        try {
            val ok = g.readCharacteristic(ch)
            if (!ok) return null
            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            return null
        } finally {
            pendingRead = null
        }
        return out
    }

    @Volatile
    private var pendingRead: ((ByteArray?) -> Unit)? = null

    private fun recordRead(uuid: String, value: ByteArray, status: Int) {
        val short = shortUuid(uuid)
        val hex = if (value.isEmpty()) "(空)" else value.joinToString(" ") { "%02X".format(it) }
        val text = if (status == BluetoothGatt.GATT_SUCCESS) {
            "READ %s [%d字节] -> %s".format(short, value.size, hex)
        } else {
            "READ %s -> 失败：%s".format(short, gattStatusHint(status))
        }
        Logx.i(TAG, text)
        // 唤醒同步读取（如果有人在等）
        pendingRead?.let { cb ->
            pendingRead = null
            cb(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
        _state.value = _state.value.copy(
            reads = (_state.value.reads + text).takeLast(60),
        )
    }

    private fun recordNotification(uuid: String, data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }.take(200)
        Logx.i(TAG, "NOTIFY $uuid -> $hex")
        val cur = _state.value
        val list = (cur.notifications + NotifyRecord(uuid, System.currentTimeMillis(), hex))
            .takeLast(80)
        _state.value = cur.copy(
            notifications = list,
            status = "收到通知：${shortUuid(uuid)} -> $hex",
        )
    }

    /** 把 UUID 缩成末段（如 0000200a-... → 200a），便于日志阅读。 */
    private fun shortUuid(uuid: String): String {
        val parts = uuid.split("-")
        return if (parts.size >= 2) parts[1] else uuid
    }

    // ---------------- 主动读取：找出哪个通道有数据 ----------------

    /**
     * 对私有服务里所有可读特征主动读取一次。
     *
     * 为什么这一步重要：被动订阅只能知道"有没有事件推送"，
     * 而主动读取能看出**哪个特征承载状态**——比如读到一个递增的计数器，
     * 那它很可能就是快门计数；读到一个 0/1 值，可能是录制状态。
     */
    @SuppressLint("MissingPermission")
    fun readAllReadable() {
        val g = gatt ?: run {
            _state.value = _state.value.copy(message = "尚未连接")
            return
        }
        val results = ArrayList<String>()
        for (svc in g.services) {
            // 只看私有服务（UUID 第 2 段不是 0000，或者 de00 这类）
            for (ch in svc.characteristics) {
                val canRead = (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                if (!canRead) continue
                try {
                    g.readCharacteristic(ch)
                } catch (t: Throwable) {
                    results.add("%s 读取异常: %s".format(shortUuid(ch.uuid.toString()), t.message))
                }
            }
        }
        _state.value = _state.value.copy(
            status = "已发起批量读取，结果会陆续出现在下方",
            message = "读取可能因鉴权失败而返回错误码，那本身就是重要信息。",
        )
        Logx.i(TAG, "发起批量读取")
    }

    /**
     * 写一个探测值到指定特征，观察相机反应。
     *
     * ⚠️ 这是**实验性**写操作：协议未知，写入可能被忽略、可能报错、
     * 极端情况下可能让相机进入某种状态。只用于探测，不作为产品功能。
     */
    @SuppressLint("MissingPermission")
    fun writeProbe(shortName: String, hexBytes: String): String {
        val g = gatt ?: return "尚未连接"
        val target = g.services.flatMap { it.characteristics }
            .firstOrNull { shortUuid(it.uuid.toString()).equals(shortName, ignoreCase = true) }
            ?: return "找不到特征 $shortName"

        val canWrite = (target.properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        if (!canWrite) return "特征 $shortName 不可写"

        val bytes = hexBytes.split(" ", ",", "0x")
            .filter { it.isNotBlank() }
            .mapNotNull { it.trim().toIntOrNull(16)?.toByte() }
            .toByteArray()
        if (bytes.isEmpty()) return "字节格式无效（示例：01 00 FF）"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(
                    target,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                "写入 ${shortName} 已提交 (API返回 $rc)"
            } else {
                @Suppress("DEPRECATION")
                target.value = bytes
                @Suppress("DEPRECATION")
                val ok = g.writeCharacteristic(target)
                "写入 ${shortName} " + if (ok) "已提交" else "失败"
            }
        } catch (t: Throwable) {
            "写入异常: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor, ch: BluetoothGattCharacteristic) {
        val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        _state.value = _state.value.copy(connectedAddress = "", status = "已断开")
    }

    fun clear() {
        stopScan()
        disconnect()
        _state.value = ProbeState()
    }

    /** 导出完整探测结果（供用户复制/截图）。 */
    fun exportReport(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("=== 蓝牙探测报告 ===")
        sb.appendLine("扫描状态: ${if (s.scanning) "进行中" else "已停止"}")
        sb.appendLine("设备列表 (${s.devices.size}):")
        s.devices.forEach {
            sb.appendLine("  ${it.name}  ${it.address}  RSSI=${it.rssi}")
            if (it.serviceUuids.isNotEmpty()) {
                sb.appendLine("      广播服务: ${it.serviceUuids.joinToString(", ")}")
            }
        }
        sb.appendLine()
        sb.appendLine("已连接: ${s.connectedAddress.ifBlank { "无" }}")
        sb.appendLine("状态: ${s.status}")
        sb.appendLine()
        sb.appendLine("服务/特征:")
        if (s.services.isEmpty()) sb.appendLine("  (无)") else s.services.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("已订阅通知的特征 (${s.notifyEnabled.size}): ${s.notifyEnabled.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("收到的通知 (${s.notifications.size}):")
        s.notifications.forEach {
            sb.appendLine("  [${it.uuid}] ${it.hex}")
        }
        return sb.toString()
    }

    private fun describeProps(ch: BluetoothGattCharacteristic): String {
        val sb = StringBuilder()
        val p = ch.properties
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) sb.append("R")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) sb.append("W")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) sb.append("Wn")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) sb.append("N")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) sb.append("I")
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    /** 把常见 UUID 翻译成人能看懂的名字，便于判断。 */
    private fun describeUuid(uuid: UUID): String {
        val s = uuid.toString().lowercase()
        return when {
            s.startsWith("0000180a") -> "Device Information"
            s.startsWith("0000180f") -> "Battery"
            s.startsWith("0000180d") -> "Heart Rate"
            s.startsWith("0000180c") -> "Health Thermometer"
            s.startsWith("0000181c") -> "User Data"
            s.startsWith("0000181e") -> "Bond Management"
            s.startsWith("00001800") -> "Generic Access"
            s.startsWith("00001801") -> "Generic Attribute"
            s.startsWith("0000ff") || s.startsWith("0000fe") -> "厂商自定义 (Vendor)"
            s.contains("7c5e") || s.contains("0000fff") -> "疑似私有服务"
            else -> "未知/自定义"
        }
    }

    companion object {
        private const val TAG = "BleProbe"

        /** Android 12+ 需要显式指定 LE 传输 */
        private const val BluetoothDevice_TRANSPORT_LE = 2

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
