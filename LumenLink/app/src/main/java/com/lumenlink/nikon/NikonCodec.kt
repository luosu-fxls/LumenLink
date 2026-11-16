package com.lumenlink.nikon

import com.lumenlink.core.Logx
import com.lumenlink.ptp.ByteWriter
import com.lumenlink.ptp.DevicePropDesc
import com.lumenlink.ptp.Ptp
import com.lumenlink.ptp.hex

/**
 * 按 DevicePropDesc 里声明的 DataType 编码 DataOut 段。
 *
 * PTP 的规则很严：SetDevicePropValue 的数据类型**必须**与属性描述符一致，
 * 否则机身返回 0x201B(InvalidDevicePropFormat) 或 0x201C(InvalidDevicePropValue)。
 * 所以永远不要硬编码字节，一律按描述符来。
 */
object NikonCodec {

    private const val TAG = "NikonCodec"

    fun encode(desc: DevicePropDesc, value: String): ByteArray? = when (desc.dataType) {
        Ptp.DT_STRING -> ByteWriter(32).ptpString(value).toByteArray()
        Ptp.DT_UINT8 -> value.toIntOrNull()?.let { ByteWriter(1).u8(it and 0xFF).toByteArray() }
        Ptp.DT_INT8 -> value.toIntOrNull()?.let { ByteWriter(1).u8(it and 0xFF).toByteArray() }
        Ptp.DT_UINT16, Ptp.DT_INT16 ->
            value.toIntOrNull()?.let { ByteWriter(2).u16(it and 0xFFFF).toByteArray() }
        Ptp.DT_UINT32, Ptp.DT_INT32 ->
            value.toLongOrNull()?.let { ByteWriter(4).u32(it and 0xFFFF_FFFFL).toByteArray() }
        Ptp.DT_UINT64, Ptp.DT_INT64 ->
            value.toLongOrNull()?.let { ByteWriter(8).u64(it).toByteArray() }
        else -> {
            Logx.w(TAG, "不支持的数据类型 0x%04X，无法编码 \"$value\"".format(desc.dataType))
            null
        }
    }

    /** 把原始 DataIn 段按描述符解释成字符串（用于读回当前值）。 */
    fun decode(desc: DevicePropDesc, data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        return try {
            when (desc.dataType) {
                Ptp.DT_STRING -> com.lumenlink.ptp.ByteReader(data).ptpString()
                Ptp.DT_UINT8, Ptp.DT_INT8 -> com.lumenlink.ptp.ByteReader(data).u8().toString()
                Ptp.DT_UINT16, Ptp.DT_INT16 -> com.lumenlink.ptp.ByteReader(data).u16().toString()
                Ptp.DT_UINT32, Ptp.DT_INT32 -> com.lumenlink.ptp.ByteReader(data).u32().toString()
                Ptp.DT_UINT64, Ptp.DT_INT64 -> com.lumenlink.ptp.ByteReader(data).u64().toString()
                else -> data.hex(32)
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "解码失败: ${t.message} raw=${data.hex(32)}")
            null
        }
    }
}
