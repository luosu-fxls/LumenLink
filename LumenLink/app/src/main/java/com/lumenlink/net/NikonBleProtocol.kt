package com.lumenlink.net

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 尼康智能设备（SnapBridge）BLE 配对协议。
 *
 * ## 为什么必须有这一步
 *
 * 实测结论：Z6II 在「连接至智能设备 → Wi-Fi 模式」下，相机**只开热点、不开放任何
 * TCP 端口**（1-10000 全扫结果为全关，mDNS 也零应答）。此时无论怎么连 WiFi 都
 * 连不上 PTP/IP。
 *
 * 原因是：**相机必须先通过 BLE 完成"智能设备配对"，才会对外提供服务。**
 * SnapBridge 能连，正是因为它做了这个配对。
 *
 * ## 协议来源
 *
 * 社区逆向结果（hurui200320/nsg, skyblond.info）。本文件是 Kotlin 移植。
 * 只保证在 2026 年 7 月前后的固件上有效，尼康可能通过固件更新改变流程。
 *
 * ## 握手流程（4 阶段，每阶段 17 字节，小端序）
 *
 * | 阶段 | 方向         | 内容 |
 * |------|--------------|------|
 * | 1    | 手机 → 相机  | stage=1, 随机 timestamp, 随机 device(首字节必须 0x01), 随机 nonce |
 * | 2    | 相机 → 手机  | stage=2, 相机的 timestamp/device/nonce（其中 device/nonce 是 challenge） |
 * | 3    | 手机 → 相机  | stage=3, 用 Blowfish 哈希算出的应答 |
 * | 4    | 相机 → 手机  | stage=4, 前 9~16 字节是相机序列号（ASCII） |
 *
 * 消息布局：`stage(1) | timestamp(8) | device(4) | nonce(4)`，全部小端序。
 */
object NikonBleProtocol {

    /** 尼康智能设备的 GATT 服务 UUID。 */
    val SERVICE = "0000de00-3dd4-4255-8d62-6dc7b9bd5561"

    /** 配对握手用（写消息 + 收 indication）。 */
    val CHAR_PAIR = "00002000-3dd4-4255-8d62-6dc7b9bd5561"

    /** 配对成功后写入本机设备名。 */
    val CHAR_ID = "00002002-3dd4-4255-8d62-6dc7b9bd5561"

    /** 写入时间。 */
    val CHAR_TIME = "00002006-3dd4-4255-8d62-6dc7b9bd5561"

    /** 写入地理位置。 */
    val CHAR_GEO = "00002007-3dd4-4255-8d62-6dc7b9bd5561"

    /** 配对期间接收相机通知（阶段 2/4）。 */
    val CHAR_NOT1 = "00002008-3dd4-4255-8d62-6dc7b9bd5561"

    /** 配对模式的广播标志：Manufacture Data 前两字节小端应为 0x0399 表示"已配对"。 */
    const val PAIRED_MARKER = 0x0399

    // ---------------- 消息结构 ----------------

    /**
     * 17 字节配对消息。
     *
     * 布局（小端序）：
     * ```
     * byte 0       stage
     * bytes 1-8    timestamp (uint64)
     * bytes 9-12   device    (uint32)
     * bytes 13-16  nonce     (uint32)
     * ```
     */
    data class PairingMessage(
        val stage: Int,
        val timestamp: Long,
        val device: Long,
        val nonce: Long,
    ) {
        fun encode(): ByteArray {
            val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(stage.toByte())
            buf.putLong(timestamp)
            buf.putInt((device and 0xFFFFFFFFL).toInt())
            buf.putInt((nonce and 0xFFFFFFFFL).toInt())
            return buf.array()
        }

        companion object {
            fun decode(data: ByteArray): PairingMessage? {
                if (data.size < 17) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val stage = buf.get().toInt() and 0xFF
                val timestamp = buf.getLong()
                val device = buf.getInt().toLong() and 0xFFFFFFFFL
                val nonce = buf.getInt().toLong() and 0xFFFFFFFFL
                return PairingMessage(stage, timestamp, device, nonce)
            }
        }
    }

    // ---------------- Blowfish 哈希 ----------------

    /**
     * 尼康握手用的自定义 Blowfish 哈希。
     *
     * 底层是标准 Blowfish / ECB / NoPadding，固定 8 字节密钥 `FF FF AA 55 11 22 33 00`。
     * 初始链值 `0x01020304 / 0x05060708`，输入按大端 32 位字分块与链值异或后再加密。
     */
    private object BlowfishHasher {
        private val KEY = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0x55.toByte(),
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x00.toByte(),
        )

        @SuppressLint("GetInstance")
        private val cipher: Cipher = Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "Blowfish"))
        }

        /** [blocks] 必须是偶数个 32 位大端字；返回最终 (left, right)。 */
        @Synchronized
        fun hash(blocks: IntArray): Pair<Int, Int> {
            require(blocks.size % 2 == 0) { "blocks 长度必须为偶数" }
            var left = 0x01020304
            var right = 0x05060708
            var i = 0
            while (i < blocks.size) {
                val inL = blocks[i] xor left
                val inR = blocks[i + 1] xor right
                val enc = encryptBlock(inL, inR)
                left = enc.first
                right = enc.second
                i += 2
            }
            return left to right
        }

        private fun encryptBlock(left: Int, right: Int): Pair<Int, Int> {
            val input = ByteBuffer.allocate(8)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(left)
                .putInt(right)
                .array()
            val out = cipher.update(input)
                ?: throw IllegalStateException("Blowfish 加密失败")
            val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
            return buf.getInt() to buf.getInt()
        }
    }

    /** 8 组预置 salt，握手时用于匹配相机的 challenge。 */
    private val SALTS = arrayOf(
        intArrayOf(0x704066e4, 0x0433d552),
        intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b),
        intArrayOf(0x24471f11, 0x8b5ea1fc.toInt()),
        intArrayOf(0x05960c31, 0x2b8c7f41),
        intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()),
        intArrayOf(0x99166056.toInt(), 0x1bd3d550),
        intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()),
        intArrayOf(0x2a8fe834.toInt(), 0xdec7ebf4.toInt()),
    )

    // ---------------- 握手引擎 ----------------

    private val random = SecureRandom()

    /** 已配对的相机身份（重连时复用 device/nonce）。 */
    data class PairedCamera(
        val device: Long,
        val nonce: Long,
        val serial: String = "",
    )

    /** 生成阶段 1 消息。传入 [saved] 则复用其中的 device/nonce（重连模式）。 */
    fun createStage1(saved: PairedCamera? = null): PairingMessage {
        val timestamp = random.nextLong()
        // 首字节必须是 0x01：小端 uint32 的 LSB
        val device = saved?.device
            ?: ((random.nextInt().toLong() and 0xFFFFFF00L.toInt().toLong()) or 0x01L)
        val nonce = saved?.nonce
            ?: (random.nextInt().toLong() and 0xFFFFFFFFL)
        return PairingMessage(stage = 0x01, timestamp = timestamp, device = device, nonce = nonce)
    }

    /**
     * 用相机返回的阶段 2 消息，验证 challenge 并生成阶段 3 应答。
     *
     * @return 阶段 3 消息；若所有 salt 都匹配不上则返回 null（协议不被接受）。
     */
    fun verifyStage2AndBuildStage3(
        stage1: PairingMessage,
        stage2: PairingMessage,
    ): PairingMessage? {
        val saltIdx = findSalt(stage1, stage2) ?: return null
        val (ourLo, ourHi) = timestampHalvesBe(stage1.timestamp)
        val (camLo, camHi) = timestampHalvesBe(stage2.timestamp)
        val blocks = intArrayOf(
            SALTS[saltIdx][0], SALTS[saltIdx][1],
            ourLo, ourHi,
            camLo, camHi,
        )
        val (r0, r1) = BlowfishHasher.hash(blocks)
        return PairingMessage(
            stage = 0x03,
            timestamp = stage1.timestamp,
            device = Integer.reverseBytes(r0).toLong() and 0xFFFFFFFFL,
            nonce = Integer.reverseBytes(r1).toLong() and 0xFFFFFFFFL,
        )
    }

    /** 从阶段 4 消息中提取 8 字节 ASCII 相机序列号。 */
    fun extractSerial(stage4: PairingMessage): String {
        val bytes = stage4.encode()
        return try {
            String(bytes.copyOfRange(9, 17), Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun findSalt(stage1: PairingMessage, stage2: PairingMessage): Int? {
        val (camLo, camHi) = timestampHalvesBe(stage2.timestamp)
        val (ourLo, ourHi) = timestampHalvesBe(stage1.timestamp)
        val camDeviceBe = Integer.reverseBytes((stage2.device and 0xFFFFFFFFL).toInt())
        val camNonceBe = Integer.reverseBytes((stage2.nonce and 0xFFFFFFFFL).toInt())
        for (i in SALTS.indices) {
            val blocks = intArrayOf(
                SALTS[i][0], SALTS[i][1],
                camLo, camHi,
                ourLo, ourHi,
            )
            val (h0, h1) = BlowfishHasher.hash(blocks)
            if (h0 == camDeviceBe && h1 == camNonceBe) return i
        }
        return null
    }

    private fun timestampHalvesBe(timestamp: Long): Pair<Int, Int> {
        val lo = (timestamp and 0xFFFFFFFFL).toInt()
        val hi = ((timestamp ushr 32) and 0xFFFFFFFFL).toInt()
        return Integer.reverseBytes(lo) to Integer.reverseBytes(hi)
    }

    /**
     * 解析 BLE 广播里的 Manufacture Data，判断相机是否已配对。
     *
     * 已配对时前两字节小端为 [PAIRED_MARKER]，随后 4 字节是 device id。
     */
    fun parseAdvertisement(manufacturerData: ByteArray?): AdvertInfo {
        if (manufacturerData == null || manufacturerData.size < 6) {
            return AdvertInfo(paired = false, deviceId = 0L)
        }
        val marker = ((manufacturerData[1].toInt() and 0xFF) shl 8) or
            (manufacturerData[0].toInt() and 0xFF)
        if (marker != PAIRED_MARKER) {
            return AdvertInfo(paired = false, deviceId = 0L)
        }
        val deviceId = ((manufacturerData[5].toLong() and 0xFF) shl 24) or
            ((manufacturerData[4].toLong() and 0xFF) shl 16) or
            ((manufacturerData[3].toLong() and 0xFF) shl 8) or
            (manufacturerData[2].toLong() and 0xFF)
        return AdvertInfo(paired = true, deviceId = deviceId)
    }

    data class AdvertInfo(val paired: Boolean, val deviceId: Long)
}
