package com.lumenlink

import android.app.Application
import com.lumenlink.core.CrashLog
import com.lumenlink.core.Logx
import com.lumenlink.link.LinkController

class App : Application() {

    lateinit var link: LinkController
        private set

    /** Application 初始化时如果炸了，在这里留下痕迹，界面能显示出来。 */
    var initError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)

        CrashLog.readLast(this)?.let {
            Logx.w("App", "检测到上次崩溃记录，可在诊断中查看")
        }

        // LinkController 的构造里会初始化闪光灯等组件，任何异常都不该让整个 App 起不来
        try {
            link = LinkController(this)
        } catch (t: Throwable) {
            initError = "${t.javaClass.simpleName}: ${t.message}"
            Logx.e("App", "LinkController 初始化失败，使用降级实例", t)
            link = LinkController.createFallback(this, t)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
