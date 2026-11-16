package com.lumenlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lumenlink.App
import com.lumenlink.R
import com.lumenlink.core.Logx
import com.lumenlink.ui.MainActivity

/**
 * 前台服务：补光与相机联动的「保活载体」。
 *
 * 为什么必须要有：
 *  1. 锁屏/切后台后，进程随时可能被回收 —— 手电筒会跟着灭，现场补光直接中断；
 *  2. Android 10+ 后台启动限制下，没有 FGS 就无法长期持有一个「正在工作的相机资源」；
 *  3. FGS 同时提供常驻通知，用户永远能一键关灯（防止 LED 卡在常亮状态烧掉）。
 *
 * 前台服务类型（API 34 强制声明）：
 *  - specialUse：Google 没有为「手电筒/补光」提供专门类型，官方推荐 specialUse + property 说明；
 *  - microphone：音频触发需要持续读麦，必须叠加该类型（且要 RECORD_AUDIO 已授权）。
 *
 * 生命周期策略（对应「退出应用要自动关灯」的硬要求）：
 *  - 用户划掉任务卡片 → [onTaskRemoved] → 关灯 + 停服务；
 *  - 服务被销毁 → [onDestroy] → 关灯（兜底，绝不留下常亮 LED）；
 *  - 锁屏/切后台 → **继续工作**（这正是本 App 存在的意义）。
 */
class FlashLinkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumenLink::led")
            ?.apply {
                setReferenceCounted(false)
                try {
                    acquire(3 * 60 * 60 * 1000L) // 3 小时上限，防止异常情况下永久占锁
                } catch (t: Throwable) {
                    Logx.w(TAG, "wakelock 获取失败: ${t.message}")
                }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                App.instance.link.disarm()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * 启动前台服务。
     *
     * ⚠️ 这里**不再声明 microphone 前台服务类型**。
     *
     * 原因（用户要求）：只要 App 不在前台就绝不能录音。
     * 声明 microphone 类型会让系统认为本 App 需要在后台合法持有麦克风，
     * 状态栏持续显示麦克风指示，用户无法确认"它到底停没停"。
     *
     * 现在只保留 specialUse（补光保活），录音完全由 Activity 的
     * 可见性驱动：onStart 才开始，onStop 立刻停。服务本身不碰麦克风。
     */
    private fun startAsForeground() {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Logx.i(TAG, "前台服务已启动 (type=$type)")
        } catch (t: Throwable) {
            // Android 12+ 从后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException
            Logx.e(TAG, "startForeground 失败", t)
            stopSelfSafely()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logx.i(TAG, "任务被移除，关闭补光")
        try {
            App.instance.link.disarm()
        } catch (t: Throwable) {
            Logx.w(TAG, "disarm 失败: ${t.message}")
        }
        stopSelfSafely()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Logx.i(TAG, "服务销毁，兜底关灯")
        try {
            App.instance.link.disarm()
        } catch (t: Throwable) {
            // 应用被整体回收时 App.instance 可能已不可用，忽略
        }
        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Throwable) {
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FlashLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = try {
            App.instance.link.uiState.value
        } catch (t: Throwable) {
            null
        }
        val mode = when (state?.sync?.mode) {
            com.lumenlink.sync.LightMode.PULSE -> "脉冲同步"
            else -> "常亮补光"
        }
        val cam = state?.cameraModel?.takeIf { it.isNotBlank() } ?: "监听快门声"
        val text = buildString {
            append(mode).append(" · ").append(cam)
            // 如实反映麦克风状态：退到后台后录音已停，通知里不能再显示"触发中"
            val micLive = try {
                App.instance.link.bystanderState.value.listening
            } catch (_: Throwable) {
                false
            }
            if (micLive) append(" · 正在听快门")
            val trig = state?.sync?.totalTriggerCount ?: 0
            if (trig > 0) append(" · 已联动 $trig 次")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "FlashLinkService"
        private const val CHANNEL_ID = "lumenlink_led"
        private const val NOTIF_ID = 4211
        const val ACTION_STOP = "com.lumenlink.action.STOP"

        /**
         * 启动前台服务（仅用于补光保活）。
         *
         * 不再接受 withMic 参数：本 App 的麦克风只在界面可见时工作，
         * 服务不承担录音职责。
         */
        fun start(context: Context) {
            val intent = Intent(context, FlashLinkService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Logx.e(TAG, "启动服务失败", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FlashLinkService::class.java))
            } catch (t: Throwable) {
                Logx.w(TAG, "停止服务失败: ${t.message}")
            }
        }
    }
}
