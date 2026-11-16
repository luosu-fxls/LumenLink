package com.lumenlink.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.lumenlink.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 尼康蓝牙配对执行器。
 *
 * 职责：连上相机的 `0000de00-...` 服务，跑完 4 阶段配对握手，
 * 拿到相机序列号后把 [NikonBleProtocol.PairedCamera] 保存下来。
 *
 * 配对成功后相机才会对外提供 WiFi 服务 —— 这是 WiFi 遥控能用的前提。
 *
 * ## GATT 操作必须严格串行
 *
 * Android 的 GATT 不支持并发操作：上一条写/读没回调就发下一条，
 * 后续操作会被静默丢弃。所以这里用 [queue] 串行推进状态机，
 * 每个回调里只做一件事，然后驱动下一步。
 */
@SuppressLint("MissingPermission")
class NikonBlePairing(private val context: Context) {

    private companion object {
        const val TAG = "NikonBle"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /** 配对进度状态。 */
    data class PairingState(
        val running: Boolean = false,
        val step: String = "",
        val log: List<String> = emptyList(),
        val serial: String = "",
        val success: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state

    private var gatt: BluetoothGatt? = null
    private var stage1: NikonBleProtocol.PairingMessage? = null
    private var saved: NikonBleProtocol.PairedCamera? = null

    /** 当前阶段：0=未开始 1=已发stage1 2=已发stage3 3=完成 */
    private var phase = 0

    private fun log(line: String) {
        Logx.i(TAG, line)
        _state.value = _state.value.copy(log = (_state.value.log + line).takeLast(60))
    }

    private fun setStep(step: String) {
        _state.value = _state.value.copy(step = step)
    }

    /**
     * 开始配对。
     *
     * @param device 相机 BLE 设备
     * @param knownSerial 之前配对成功过时传入，用于重连模式
     */
    fun start(device: BluetoothDevice, savedCamera: NikonBleProtocol.PairedCamera? = null) {
        close()
        saved = savedCamera
        phase = 0
        _state.value = PairingState(
            running = true,
            step = "正在连接蓝牙…",
            message = null,
        )
        log("连接 ${device.address} (${device.name ?: "?"})")
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            fail("连接蓝牙失败: ${t.message}")
        }
    }

    fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        phase = 0
    }

    private fun fail(msg: String) {
        log("✗ $msg")
        _state.value = _state.value.copy(
            running = false,
            success = false,
            message = msg,
        )
        close()
    }

    private fun succeed(serial: String) {
        log("✓ 配对完成，相机序列号: $serial")
        _state.value = _state.value.copy(
            running = false,
            success = true,
            serial = serial,
            step = "配对完成",
            message = "配对成功（序列号 $serial）。现在请重新连接相机 WiFi，" +
                "相机应该会开放 PTP/IP 服务了。",
        )
        close()
    }

    // ---------------- GATT 回调 ----------------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("连接状态 status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStep("已连接，请求 MTU…")
                // 增大 MTU，保证 17 字节消息一次写完
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (phase != 3 && _state.value.running) {
                    fail("蓝牙连接断开 (status=$status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu status=$status")
            setStep("发现服务…")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("服务发现 status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 status=$status")
                return
            }
            val svc = g.getService(UUID.fromString(NikonBleProtocol.SERVICE))
            if (svc == null) {
                fail("找不到尼康服务 ${NikonBleProtocol.SERVICE}")
                return
            }
            val pair = svc.getCharacteristic(UUID.fromString(NikonBleProtocol.CHAR_PAIR))
            val not1 = svc.getCharacteristic(UUID.fromString(NikonBleProtocol.CHAR_NOT1))
            if (pair == null || not1 == null) {
                fail("找不到 PAIR/NOT1 特征")
                return
            }
            log("PAIR props=${pair.properties} NOT1 props=${not1.properties}")

            setStep("订阅配对通知…")
            // 先开 PAIR 的 indication，成功后回调里再开 NOT1
            try {
                g.setCharacteristicNotification(pair, true)
                val d = pair.getDescriptor(CCCD)
                if (d == null) {
                    fail("PAIR 特征没有 CCCD 描述符")
                    return
                }
                writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } catch (t: Throwable) {
                fail("订阅 PAIR 失败: ${t.message}")
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("描述符写入失败 status=$status")
                return
            }
            val chUuid = descriptor.characteristic?.uuid?.toString()
            log("描述符写入完成 $chUuid")

            if (chUuid == NikonBleProtocol.CHAR_PAIR) {
                // PAIR 订阅好了，接着订阅 NOT1
                val svc = g.getService(UUID.fromString(NikonBleProtocol.SERVICE))
                val not1 = svc?.getCharacteristic(UUID.fromString(NikonBleProtocol.CHAR_NOT1))
                if (not1 == null) {
                    fail("找不到 NOT1 特征")
                    return
                }
                try {
                    g.setCharacteristicNotification(not1, true)
                    val d = not1.getDescriptor(CCCD)
                    if (d == null) {
                        fail("NOT1 没有 CCCD")
                        return
                    }
                    writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } catch (t: Throwable) {
                    fail("订阅 NOT1 失败: ${t.message}")
                }
            } else if (chUuid == NikonBleProtocol.CHAR_NOT1) {
                // 两个订阅都好了，开始握手
                sendStage1(g)
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(ch.uuid, value)
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotify(ch.uuid, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log("写入 ${shortUuid(ch.uuid)} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("写入失败 status=$status")
            }
        }
    }

    // ---------------- 握手状态机 ----------------

    private fun sendStage1(g: BluetoothGatt) {
        setStep("发送阶段 1（配对请求）…")
        val msg = NikonBleProtocol.createStage1(saved)
        stage1 = msg
        phase = 1
        log("stage1: device=0x%08X nonce=0x%08X".format(msg.device, msg.nonce))
        writeCharacteristic(g, NikonBleProtocol.CHAR_PAIR, msg.encode())
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        val uuidStr = uuid.toString()
        val hex = value.joinToString(" ") { "%02X".format(it) }
        log("收到通知 ${shortUuid(uuid)} [${value.size}] $hex")

        if (uuidStr != NikonBleProtocol.CHAR_PAIR && uuidStr != NikonBleProtocol.CHAR_NOT1) return

        val msg = NikonBleProtocol.PairingMessage.decode(value)
        if (msg == null) {
            log("消息长度不足 17 字节，忽略")
            return
        }
        val g = gatt ?: return

        when (msg.stage) {
            0x02 -> {
                val s1 = stage1 ?: run {
                    fail("未发送阶段1 却收到阶段2")
                    return
                }
                setStep("校验挑战并生成应答…")
                val s3 = NikonBleProtocol.verifyStage2AndBuildStage3(s1, msg)
                if (s3 == null) {
                    fail("挑战校验失败：8 组 salt 都不匹配。可能固件已改变协议。")
                    return
                }
                phase = 2
                log("stage3 应答已生成")
                writeCharacteristic(g, NikonBleProtocol.CHAR_PAIR, s3.encode())
            }

            0x04 -> {
                val serial = NikonBleProtocol.extractSerial(msg)
                phase = 3
                if (serial.isBlank() || serial.any { it.code < 0x20 || it.code > 0x7E }) {
                    // 序列号非法也继续，只要握手走完就算成功
                    log("阶段4 序列号看起来不合法: '$serial'")
                }
                // 配对完成后写入设备名，让相机记住我们
                writeDeviceName(g)
                succeed(serial)
            }

            else -> log("未知阶段 ${msg.stage}")
        }
    }

    private fun writeDeviceName(g: BluetoothGatt) {
        try {
            val name = "LumenLink"
            val bytes = name.toByteArray(Charsets.US_ASCII)
            writeCharacteristic(g, NikonBleProtocol.CHAR_ID, bytes)
            log("已写入设备名 $name")
        } catch (t: Throwable) {
            log("写设备名失败（不影响配对）: ${t.message}")
        }
    }

    // ---------------- GATT 工具 ----------------

    private fun writeCharacteristic(g: BluetoothGatt, uuid: String, data: ByteArray) {
        val svc = g.getService(UUID.fromString(NikonBleProtocol.SERVICE)) ?: run {
            fail("找不到服务")
            return
        }
        val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: run {
            fail("找不到特征 $uuid")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (t: Throwable) {
            fail("写入异常: ${t.message}")
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value)
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        } catch (t: Throwable) {
            fail("写描述符异常: ${t.message}")
        }
    }

    private fun shortUuid(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            s.substring(4, 8)
        } else {
            s
        }
    }
}
