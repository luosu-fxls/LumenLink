package com.lumenlink.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获：把未捕获异常的完整堆栈落盘。
 *
 * 为什么必须有：真机上一崩，环形日志（Logx）跟着进程一起消失，用户手上只剩启动那两行，
 * 等于断掉了唯一的排查通道。这里把堆栈写到应用私有目录，下次启动时读出来展示。
 *
 * 注意：不改变崩溃行为（写完仍然转交默认处理器），只做记录。
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val TAG = "CrashLog"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                    pw.println("线程: ${thread.name}")
                    pw.println("=== 最近日志 ===")
                    pw.println(Logx.dump())
                    pw.println("=== 堆栈 ===")
                    throwable.printStackTrace(pw)
                }
                File(appContext.filesDir, FILE_NAME).writeText(sw.toString())
                android.util.Log.e(TAG, "崩溃已记录到 $FILE_NAME")
            } catch (_: Throwable) {
                // 记录本身失败也不能影响崩溃流程
            }
            // 交回默认处理器，保持系统行为
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 读取上次崩溃记录；没有则返回 null。读完不删除，方便重复查看。 */
    fun readLast(context: Context): String? = try {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
