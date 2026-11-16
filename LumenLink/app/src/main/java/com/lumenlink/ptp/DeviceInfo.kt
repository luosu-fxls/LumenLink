package com.lumenlink.ptp

import com.lumenlink.core.Logx

/**
 * GetDeviceInfo(0x1001) 的返回体，容错解析。
 * 只解析本工程真正用得到的字段（能力位图 + 型号字符串），其余按偏移跳过。
 */
data class DeviceInfo(
    val standardVersion: Int = 0,
    val vendorExtensionId: Long = 0,
    val vendorExtensionVersion: Int = 0,
    val vendorExtensionDesc: String = "",
    val operationsSupported: Set<Int> = emptySet(),
    val eventsSupported: Set<Int> = emptySet(),
    val devicePropertiesSupported: Set<Int> = emptySet(),
    val manufacturer: String = "",
    val model: String = "",
    val deviceVersion: String = "",
    val serialNumber: String = "",
) {
    fun describe(): String =
        "vendor=0x%04X(%s) model=\"%s\" fw=%s sn=%s ops=%d events=%d props=%d".format(
            vendorExtensionId, vendorExtensionDesc, model, deviceVersion, serialNumber,
            operationsSupported.size, eventsSupported.size, devicePropertiesSupported.size
        )

    companion object {
        private const val TAG = "DeviceInfo"

        fun parse(data: ByteArray): DeviceInfo {
            val r = ByteReader(data)
            val stdVer = r.u16()
            val vendorId = r.u32()
            val vendorVer = r.u16()
            val vendorDesc = try { r.ptpString() } catch (t: Throwable) { "" }
            r.u16() // FunctionalMode
            val ops = readU16Set(r)
            val events = readU16Set(r)
            val props = readU16Set(r)
            readU16Set(r) // CaptureFormats
            readU16Set(r) // ImageFormats

            val manufacturer = safeString(r)
            val model = safeString(r)
            val deviceVersion = safeString(r)
            val serial = safeString(r)

            val info = DeviceInfo(
                standardVersion = stdVer,
                vendorExtensionId = vendorId,
                vendorExtensionVersion = vendorVer,
                vendorExtensionDesc = vendorDesc,
                operationsSupported = ops,
                eventsSupported = events,
                devicePropertiesSupported = props,
                manufacturer = manufacturer,
                model = model,
                deviceVersion = deviceVersion,
                serialNumber = serial,
            )
            Logx.i(TAG, info.describe())
            return info
        }

        private fun readU16Set(r: ByteReader): Set<Int> {
            if (r.remaining < 4) return emptySet()
            val n = r.u32().toInt()
            if (n < 0 || n > 4096 || r.remaining < n * 2) return emptySet()
            val out = LinkedHashSet<Int>(n)
            for (i in 0 until n) out.add(r.u16())
            return out
        }

        private fun safeString(r: ByteReader): String = try {
            if (r.remaining <= 0) "" else r.ptpString()
        } catch (t: Throwable) {
            ""
        }
    }
}
