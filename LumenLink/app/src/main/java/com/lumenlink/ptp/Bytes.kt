package com.lumenlink.ptp

/**
 * PTP 全部使用小端序（PIMA 15740）。这里手写读写器，避免依赖 java.nio 的 ByteOrder 反复切换，
 * 也方便在解析失败时精确报出偏移量——真机对码时这点很重要。
 */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = buf.size - pos
    val offset: Int get() = pos

    fun eof(): Boolean = pos >= buf.size

    private fun need(n: Int) {
        require(pos + n <= buf.size) { "越界读取: 需要 $n 字节, 偏移 $pos, 总长 ${buf.size}" }
    }

    fun u8(): Int {
        need(1); return buf[pos++].toInt() and 0xFF
    }

    fun i8(): Int = u8().let { if (it > 127) it - 256 else it }

    fun u16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun i16(): Int = u16().let { if (it > 0x7FFF) it - 0x10000 else it }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        need(n); pos += n
    }

    /**
     * PTP 字符串：1 字节长度（含结尾 '\0' 的字符数）+ UTF-16LE。
     * 这是 15740:2000 之后的写法，绝大多数机身如此。
     */
    fun ptpString(): String {
        val len = u8()
        if (len == 0) return ""
        val raw = bytes(len * 2)
        return String(raw, Charsets.UTF_16LE).trimEnd('\u0000')
    }

    /** 少数老固件用 2 字节长度前缀，容错用。 */
    fun ptpString16(): String {
        val len = u16()
        if (len == 0) return ""
        val raw = bytes(len * 2)
        return String(raw, Charsets.UTF_16LE).trimEnd('\u0000')
    }
}

class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    private var pos = 0

    private fun ensure(n: Int) {
        if (pos + n <= buf.size) return
        var cap = buf.size
        while (cap < pos + n) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int): ByteWriter {
        ensure(1); buf[pos++] = v.toByte(); return this
    }

    fun u16(v: Int): ByteWriter {
        ensure(2)
        buf[pos++] = (v and 0xFF).toByte()
        buf[pos++] = ((v ushr 8) and 0xFF).toByte()
        return this
    }

    fun u32(v: Long): ByteWriter {
        ensure(4)
        for (i in 0 until 4) buf[pos++] = ((v ushr (8 * i)) and 0xFF).toByte()
        return this
    }

    fun u64(v: Long): ByteWriter {
        ensure(8)
        for (i in 0 until 8) buf[pos++] = ((v ushr (8 * i)) and 0xFF).toByte()
        return this
    }

    fun raw(b: ByteArray): ByteWriter {
        ensure(b.size)
        b.copyInto(buf, pos)
        pos += b.size
        return this
    }

    /** PTP 字符串：1 字节长度（含 '\0'）+ UTF-16LE */
    fun ptpString(s: String): ByteWriter {
        val chars = s.length + 1
        require(chars <= 255) { "PTP 字符串过长" }
        u8(chars)
        val body = (s + "\u0000").toByteArray(Charsets.UTF_16LE)
        return raw(body)
    }

    val size: Int get() = pos

    fun toByteArray(): ByteArray = buf.copyOf(pos)

    /** 取内部缓冲引用（用于零拷贝发送），配合 [size] 使用。 */
    fun buffer(): ByteArray = buf
}

fun ByteArray.hex(limit: Int = 96): String {
    val n = minOf(size, limit)
    val sb = StringBuilder(n * 3)
    for (i in 0 until n) {
        val v = this[i].toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F]).append(' ')
    }
    if (size > limit) sb.append("...(+${size - limit}B)")
    return sb.toString().trim()
}

private val HEX = "0123456789ABCDEF".toCharArray()
