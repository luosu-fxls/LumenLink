package com.lumenlink.ui

import android.content.Context

/**
 * 教程是否已读过的本地标记。
 *
 * 首次启动自动弹出教程，之后从主界面的「使用教程」按钮进入。
 * 用 SharedPreferences 而不是 DataStore，因为只有一个布尔值，不值得引入依赖。
 */
object TutorialPrefs {
    private const val FILE = "lumenlink_prefs"
    private const val KEY_SEEN = "tutorial_seen"

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
    }
}
