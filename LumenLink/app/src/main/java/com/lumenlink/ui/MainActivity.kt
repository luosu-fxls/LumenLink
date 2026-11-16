package com.lumenlink.ui

import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lumenlink.App
import com.lumenlink.core.Logx
import com.lumenlink.link.LinkController


/** 记录"是否向用户发起过麦克风权限请求"，用来识别「拒绝且不再询问」。 */
private const val KEY_MIC_ASKED = "mic_permission_asked"

/** 应用内页面。用枚举代替 navigation 依赖，只有三个页面不值得引入库。 */enum class AppScreen {
    TUTORIAL,
    SETTINGS,
    MAIN;

    /** 页面栈深度，用来决定切换动画方向：数字变大=往深处走（滑入），变小=返回（滑出）。 */
    val depth: Int
        get() = when (this) {
            MAIN -> 0
            SETTINGS -> 1
            TUTORIAL -> 1
        }
}

class MainActivity : ComponentActivity() {

    /**
     * 麦克风授权状态。用 Compose 的 MutableState 而不是每次 checkSelfPermission：
     * 权限弹窗关闭后界面要**立刻**从"没有权限"切到可用状态，靠轮询或
     * 只在 onCreate 读一次都会慢半拍。
     */
    private var micGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Logx.i("MainActivity", "权限结果: $result")
        // 只更新麦克风这一项，其它权限（相机/通知）与本界面无关
        result.forEach { (perm, granted) ->
            if (perm == Manifest.permission.RECORD_AUDIO) micGranted.value = granted
        }
        App.instance.link.clearMessage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全面屏：内容延伸到状态栏/导航栏下方，返回手势与左右滑动手势在
        // 系统层面生效（Android 10+ 的全面屏手势本身由系统接管，这里做的是
        // 让界面真正铺满，并配合下面的 BackHandler 让返回键按"页面栈"退出）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 每次启动先读一次真实权限状态：用户可能在系统设置里撤销了授权
        micGranted.value = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        requestStartupPermissions()

        val link = App.instance.link
        // 恢复用户偏好：颜色主题 + 采样条数
        link.setSampleTarget(SamplePrefs.load(this))
        val startAccent = AccentPrefs.load(this)
        val startScreen = if (TutorialPrefs.seen(this)) AppScreen.MAIN else AppScreen.TUTORIAL
        setContent {
            // ⚠️ Compose 不允许把 Composable 调用包进 try/catch（编译器直接拒绝），
            // 所以渲染期兜底靠两样东西：
            //  1. 所有回调在**调用点**就各自 runCatching，异常不会进入组合；
            //  2. 数据源本身（LinkController.arm/diagnostics…）内部已逐段兜底。
            var accentSel by remember { mutableStateOf(startAccent) }
            LumenTheme(accent = accentSel.preset, customRgb = accentSel.customRgb) {
                val ui by link.uiState.collectAsState()
                val bystander by link.bystanderState.collectAsState()

                // 页面栈用单个枚举表示，避免引入 navigation 依赖。
                // 首次启动：教程 → 设置（引导训练音色）→ 主界面
                // 记住"设置页是从哪来的"：从教程进来是新手引导（不显示历史统计），
                // 从主界面点"设置"进来才是完整设置页。
                // 起始页由 TutorialPrefs 决定：装完第一次进教程，之后每次都直接进主界面。
                // 之前这里硬编码 TUTORIAL，导致每次冷启动都重放一遍教程
                // （上面算好的 startScreen 被完全忽略了）。
                var screen by rememberSaveable { mutableStateOf(startScreen) }
                var settingsFromTutorial by rememberSaveable { mutableStateOf(false) }

                // 返回键/返回手势：按页面栈回退，主界面再按才退出 App。
                // 不做这一层的话，返回手势会直接把用户踢出 App，连正在跑的补光都断了。
                BackHandler(enabled = screen != AppScreen.MAIN) {
                    screen = when (screen) {
                        AppScreen.TUTORIAL -> if (TutorialPrefs.seen(this@MainActivity)) {
                            AppScreen.MAIN
                        } else {
                            // 教程还没看完就返回：直接进主界面，不再拦着
                            TutorialPrefs.markSeen(this@MainActivity)
                            AppScreen.MAIN
                        }
                        AppScreen.SETTINGS -> AppScreen.MAIN
                        AppScreen.MAIN -> AppScreen.MAIN
                    }
                }

                // 页面切换动画：进入子页面从右滑入，返回时向右滑出——
                // 方向跟着"页面栈深度"走，用户能感觉到自己是进去还是退出来。
                // depth 越大越深：MAIN=0 < SETTINGS=1 < TUTORIAL=1
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState.depth >= initialState.depth
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) {
                            it * dir / 4
                        } + fadeIn(animationSpec = tween(260))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) {
                                -it * dir / 8
                            } + fadeOut(animationSpec = tween(200)))
                    },
                    label = "screen",
                ) { current ->
                when (current) {
                    AppScreen.TUTORIAL -> TutorialScreen(onClose = {
                        TutorialPrefs.markSeen(this@MainActivity)
                        // 首次看完教程直接进设置，让用户顺手把音色训练做了
                        if (bystander.timbreTrained) {
                            screen = AppScreen.MAIN
                        } else {
                            settingsFromTutorial = true
                            screen = AppScreen.SETTINGS
                        }
                    })

                    AppScreen.SETTINGS -> SettingsScreen(
                        state = bystander,
                        accentSel = accentSel,
                        showLifetime = !settingsFromTutorial,
                        onClose = { screen = AppScreen.MAIN },
                        onSelectAccent = { sel ->
                            accentSel = sel
                            AccentPrefs.save(this@MainActivity, sel)
                        },
                        onSpectrumSampling = { on -> runCatching { link.setSpectrumSampling(on) } },
                        onAutoCalibrate = {
                            runCatching { link.autoCalibrateFromSamples() }
                                .getOrElse { "校准失败: ${it.message}" }
                        },
                        onSampleTarget = { n ->
                            runCatching {
                                link.setSampleTarget(n)
                                SamplePrefs.save(this@MainActivity, n)
                            }
                        },
                        onResetLifetime = { runCatching { link.resetLifetimeTriggers() } },
                        onResetTimbre = { runCatching { link.resetTimbreTemplate() } },
                        onDenoise = { on -> runCatching { link.setDenoise(on) } },
                        onDenoiseStrength = { v -> runCatching { link.setDenoiseStrength(v) } },
                        onMicSelfCheck = {
                            runCatching { link.micSelfCheck() }
                                .getOrElse { "自检失败: ${it.message}" }
                        },
                        onRequestMic = { requestMicPermission() },
                        onShowTutorial = { screen = AppScreen.TUTORIAL },
                        hasMicPermission = micGranted.value,
                    )

                    AppScreen.MAIN -> MainScreen(
                        state = ui,
                        bystander = bystander,
                        onShowTutorial = { screen = AppScreen.TUTORIAL },
                        onOpenSettings = {
                            settingsFromTutorial = false
                            screen = AppScreen.SETTINGS
                        },
                        onStartBystander = { mode -> runCatching { link.startBystander(mode) } },
                        onStopBystander = { runCatching { link.stopBystander() } },
                        onBystanderTest = { runCatching { link.bystanderTestPulse() } },
                        onDenoise = { on -> runCatching { link.setDenoise(on) } },
                        onDenoiseStrength = { v -> runCatching { link.setDenoiseStrength(v) } },
                        onMicSelfCheck = {
                            runCatching { link.micSelfCheck() }
                                .getOrElse { "自检失败: ${it.message}" }
                        },
                        onArm = { runCatching { link.arm() } },
                        onDisarm = { runCatching { link.disarm() } },
                        onToggleMode = { mode -> runCatching { link.setLightMode(mode) } },
                        onUpdateConfig = { cfg -> runCatching { link.updateConfig(cfg) } },
                        onTestPulse = { runCatching { link.testPulse(300) } },
                        onRequestMic = { requestMicPermission() },
                        hasMicPermission = micGranted.value,
                    )
                }
                }
            }
        }
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * 回到前台时重新同步麦克风权限。
     *
     * 覆盖两种绕过回调的情况：
     *  1. 用户在系统设置里手动开启/撤销了麦克风权限
     *  2. 权限弹窗被系统接管、result 回调没走到本 Activity
     * 只读一个 int，开销可以忽略。
     */
    override fun onResume() {
        super.onResume()
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted.value != granted) {
            Logx.i("MainActivity", "麦克风权限变化: ${micGranted.value} → $granted")
            micGranted.value = granted
        }
    }

    /**
     * 界面不可见（切到其他 App / 按 Home / 锁屏 / 划掉）时，**立即且彻底**停止录音。
     *
     * 隐私底线：只要不在这个软件里，就绝不访问麦克风。
     * 实现上做了三重保证：
     *  1. 停 AudioRecord（audio.stop），硬件层不再采集
     *  2. 停采样标志，避免恢复时残留状态
     *  3. 前台服务不再声明 microphone 类型，后台也不具备合法录音身份
     *
     * 用 onStop 而非 onPause：onPause 在弹权限框、下拉通知栏时也会触发，
     * 会导致刚授权就被停掉；onStop 才对应"界面真的看不见了"。
     */
    override fun onStop() {
        super.onStop()
        stopMicForPrivacy("界面不可见")
    }

    /**
     * Activity 被销毁时再兜一层（例如系统回收、旋转重建）。
     * 重复调用是安全的：stopAudio 内部用原子标志防重入。
     */
    override fun onDestroy() {
        stopMicForPrivacy("界面销毁")
        super.onDestroy()
    }

    /** 停止一切麦克风访问。可重复调用。 */
    private fun stopMicForPrivacy(reason: String) {
        try {
            val link = App.instance.link
            val wasRunning = link.audio.isRunning() || link.bystanderState.value.listening
            if (!wasRunning) return
            Logx.i("MainActivity", "$reason → 停止录音")
            // 先关采样标志，避免录音线程在退出过程中又记录样本
            runCatching { link.setSpectrumSampling(false) }
            // 再彻底停止录音（会 release AudioRecord）
            runCatching { link.stopBystander() }
            runCatching { link.stopAudio() }
        } catch (t: Throwable) {
            Logx.w("MainActivity", "停止录音失败: ${t.message}")
        }
    }

    /** 相机插上时系统会带 USB_DEVICE_ATTACHED 直接唤起本界面，这里顺手发起连接。 */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Logx.i("MainActivity", "USB 设备接入广播，自动连接")
            runCatching { App.instance.link.connectUsb() }
        }
    }

    /**
     * 首次进入 App 时一次性申请所有必需权限。
     *
     * 麦克风是本 App 的**核心能力**——整个功能就是"听相机快门声来开补光"，
     * 没有麦克风权限等于完全不能用，所以必须和相机权限一起在启动时就要，
     * 不能等用户点"开始"才发现没权限。
     *
     * 三个权限放在同一次 launch 里：系统会把它们合成一个弹窗序列，
     * 用户一口气授权完，不用被反复打断。
     */
    private fun requestStartupPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 只申请还缺的那些，已授权的重复申请会被系统直接忽略
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            micGranted.value = true
            return
        }
        // 记下这次问过麦克风，供 requestMicPermission 区分"拒绝且不再询问"
        if (Manifest.permission.RECORD_AUDIO in missing) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_MIC_ASKED, true).apply()
        }
        runCatching { permissionLauncher.launch(missing.toTypedArray()) }
            .onFailure { Logx.w("MainActivity", "启动权限申请失败: ${it.message}") }
    }

    /**
     * 申请麦克风权限。
     *
     * 关键是处理"拒绝且不再询问"：这种情况下 requestPermission 不会再弹窗，
     * 用户点了按钮毫无反应。所以先用 shouldShowRequestPermissionRationale 判断：
     *   - 已授权 → 什么都不做
     *   - 还没问过 / 拒绝过但允许再问 → 正常弹系统权限框
     *   - 拒绝且不再询问（rationale=false 且未授权）→ 直接跳系统设置页
     */
    private fun requestMicPermission() {
        val perm = Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micGranted.value = true
            return
        }
        val askedBefore = getPreferences(MODE_PRIVATE).getBoolean(KEY_MIC_ASKED, false)
        val canAsk = !askedBefore ||
            shouldShowRequestPermissionRationale(perm)
        if (canAsk) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_MIC_ASKED, true).apply()
            runCatching { permissionLauncher.launch(arrayOf(perm)) }
        } else {
            // 系统不会再弹窗，只能引导去设置页手动开
            Logx.i("MainActivity", "麦克风权限被永久拒绝，跳转系统设置")
            openAppSettings()
        }
    }

    /** 跳到本应用的系统设置页，用户可在那里手动开启麦克风权限。 */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                )
            )
        }.onFailure { Logx.w("MainActivity", "打开系统设置失败: ${it.message}") }
    }

    /** 蓝牙权限（Android 12+ 用 SCAN/CONNECT，之前用定位）。 */
    private fun requestBlePermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        runCatching { permissionLauncher.launch(perms) }
    }
}
