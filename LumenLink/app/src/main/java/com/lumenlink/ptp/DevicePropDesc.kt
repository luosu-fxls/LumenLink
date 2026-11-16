package com.lumenlink.ptp

import com.lumenlink.core.Logx

/**
 * DevicePropDesc（0x1014 的返回体）。这是整个 App 能做到「机型无关」的关键：
 * 别去硬编码「快门速度=1/250」，而是让机身自己告诉我们它有哪些合法值。
 *
 * 布局（15740 §13.3.3）：
 *   PropertyCode(u16) DataType(u16) GetSet(u8) FactoryDefault(DataType) Current(DataType)
 *   FormFlag(u8)
 *     FormFlag=1 (Range) : Min(DataType) Max(DataType) Step(DataType)
 *     FormFlag=2 (Enum)  : NumberOfValues(u16) Value1..ValueN(DataType)
 */
data class DevicePropDesc(
    val propertyCode: Int,
    val dataType: Int,
    val getSet: Int,
    val factoryDefault: String,
    val current: String,
    val formFlag: Int,
    val rangeMin: String? = null,
    val rangeMax: String? = null,
    val rangeStep: String? = null,
    val enumValues: List<String> = emptyList(),
    val wideStrings: Boolean = false,
) {
    val writable: Boolean get() = getSet != 0

    /** UI 直接可用的候选值列表；Range 型属性返回空。 */
    val choices: List<String> get() = enumValues

    fun describe(): String = buildString {
        append("0x%04X.%s getSet=%d cur=%s".format(propertyCode, Ptp.datatypeName(dataType), getSet, current))
        when (formFlag) {
            1 -> append(" range=[$rangeMin..$rangeMax step $rangeStep]")
            2 -> append(" enum(${enumValues.size})=" + enumValues.joinToString(","))
        }
    }

    companion object {
        /**
         * 容错解析：先按标准的 1 字节字符串长度解析，失败再按 2 字节长度重试
         * （少数老固件 / 某些私扩展属性用宽长度）。
         */
        fun parse(data: ByteArray): DevicePropDesc? {
            return try {
                parseInternal(data, false)
            } catch (t: Throwable) {
                Logx.d(TAG, "窄字符串解析失败(${t.message})，尝试宽字符串")
                try {
                    parseInternal(data, true)
                } catch (t2: Throwable) {
                    Logx.w(TAG, "属性描述符解析失败: ${t2.message} raw=${data.hex(48)}")
                    null
                }
            }
        }

        private fun parseInternal(data: ByteArray, wide: Boolean): DevicePropDesc {
            val r = ByteReader(data)
            val code = r.u16()
            val dt = r.u16()
            val getSet = r.u8()
            val factory = readTyped(r, dt, wide)
            val current = readTyped(r, dt, wide)
            val formFlag = if (r.remaining > 0) r.u8() else 0

            var min: String? = null
            var max: String? = null
            var step: String? = null
            val enums = ArrayList<String>()

            when (formFlag) {
                1 -> {
                    min = readTyped(r, dt, wide)
                    max = readTyped(r, dt, wide)
                    step = readTyped(r, dt, wide)
                }
                2 -> {
                    val n = r.u16()
                    for (i in 0 until n) {
                        if (r.remaining <= 0) break
                        enums.add(readTyped(r, dt, wide))
                    }
                }
            }
            return DevicePropDesc(code, dt, getSet, factory, current, formFlag, min, max, step, enums, wide)
        }

        private fun readTyped(r: ByteReader, dt: Int, wide: Boolean): String = when (dt) {
            Ptp.DT_INT8 -> r.i8().toString()
            Ptp.DT_UINT8 -> r.u8().toString()
            Ptp.DT_INT16 -> r.i16().toString()
            Ptp.DT_UINT16 -> r.u16().toString()
            Ptp.DT_INT32 -> r.i32().toString()
            Ptp.DT_UINT32 -> r.u32().toString()
            Ptp.DT_INT64, Ptp.DT_UINT64 -> r.u64().toString()
            Ptp.DT_INT8ARRAY, Ptp.DT_UINT8ARRAY -> {
                val n = r.u32().toInt()
                r.bytes(n.coerceIn(0, 1024)).hex(16)
            }
            Ptp.DT_STRING -> if (wide) r.ptpString16() else r.ptpString()
            else -> throw IllegalArgumentException("不支持的数据类型 0x%04X".format(dt))
        }

        private const val TAG = "PropDesc"
    }
}
