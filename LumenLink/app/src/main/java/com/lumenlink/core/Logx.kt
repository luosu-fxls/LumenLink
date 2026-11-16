package com.lumenlink.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量环形日志：既写 logcat，也保留最近 N 行供 UI 内的「诊断台」展示。
 * 真机联调时（尤其 PTP 握手/属性枚举阶段）这个非常关键，省去反复连 adb 的麻烦。
 */
object Logx {

    private const val MAX_LINES = 600
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ring = ArrayDeque<String>(MAX_LINES + 1)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(tag: String, msg: String) = emit("D", tag, msg, null)
    fun i(tag: String, msg: String) = emit("I", tag, msg, null)
    fun w(tag: String, msg: String) = emit("W", tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = emit("E", tag, msg, t)

    @Synchronized
    private fun emit(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
        val suffix = if (t == null) "" else " | ${t.javaClass.simpleName}: ${t.message}"
        ring.addLast("${fmt.format(Date())} $level/$tag $msg$suffix")
        while (ring.size > MAX_LINES) ring.removeFirst()
        _lines.value = ring.toList()
    }

    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    @Synchronized
    fun clear() {
        ring.clear()
        _lines.value = emptyList()
    }
}
