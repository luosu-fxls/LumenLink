package com.lumenlink.link

import android.content.Context

/**
 * 历史累计统计（跨重启保留）。
 *
 * 与主界面的 `triggerCount` 刻意区分开：
 *  - 主界面那个是**本次运行**的计数，重启即归零，反映"这次用了多少"
 *  - 这里是**历史总量**，反映"这台手机一共补了多少次光"
 *
 * 两者意义不同，所以不做成同一个数：现场拍摄时希望看到干净的本次计数，
 * 而累计量用于了解设备使用强度，只在设置里展示。
 */
object LifetimeStats {

    private const val FILE = "lumenlink_stats"
    private const val KEY_TRIGGERS = "lifetime_triggers"

    fun triggers(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_TRIGGERS, 0)

    /** 触发一次，返回累加后的值。 */
    fun bump(context: Context): Int {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val next = sp.getInt(KEY_TRIGGERS, 0) + 1
        sp.edit().putInt(KEY_TRIGGERS, next).apply()
        return next
    }

    fun reset(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TRIGGERS, 0).apply()
    }
}
