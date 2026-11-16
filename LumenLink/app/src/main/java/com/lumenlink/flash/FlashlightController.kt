package com.lumenlink.flash

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.lumenlink.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 手电筒控制器。
 *
 * 关键点：
 * 1. 不打开预览/相机会话，纯 [CameraManager.setTorchMode] 路径，功耗与发热远低于建 CaptureSession
 *    后设 CONTROL_AE_MODE_ON + FLASH_MODE_TORCH 的做法。
 * 2. Android 13 (API 33) 起有 [CameraManager.turnOnTorchWithStrengthLevel]，
 *    配合 FLASH_INFO_STRENGTH_MAXIMUM_LEVEL 可以真正拿到「最高亮度」而不是系统缺省亮度。
 * 3. 通过 [CameraManager.TorchCallback] 感知系统/固件单方面关灯（过热保护、其他 App 抢占、
 *    相机资源被占用），这是处理「有些机型过热自动关闭」的第一手信号。
 * 4. 所有相机调用串行化并抛到 IO 线程，避免主线程 StrictMode 违规与并发抢锁。
 */
class FlashlightController(context: Context) {

    data class TorchState(
        val cameraId: String? = null,
        val hasFlash: Boolean = false,
        val ready: Boolean = false,
        val on: Boolean = false,
        val strength: Int = 0,
        val maxStrength: Int = 1,
        val supportsStrengthApi: Boolean = false,
        val lastError: String? = null,
        /** 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN */
        val thermalStatus: Int = 0,
    )

    private val appContext = context.applicationContext
    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _state = MutableStateFlow(TorchState())
    val state: StateFlow<TorchState> = _state

    private val mutex = Mutex()
    private var callbackRegistered = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != _state.value.cameraId) return
            Logx.i(TAG, "onTorchModeChanged id=$cameraId enabled=$enabled")
            _state.value = _state.value.copy(on = enabled, ready = true)
            if (!enabled && requested) {
                // 系统单方面关灯：过热 / 被抢占 / 相机资源回收
                Logx.w(TAG, "系统主动关闭了手电筒（很可能是过热保护或资源被抢占）")
                _state.value = _state.value.copy(
                    lastError = "系统关闭了手电筒（过热保护或相机被其他应用占用）"
                )
                requested = false
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != _state.value.cameraId) return
            Logx.w(TAG, "onTorchModeUnavailable id=$cameraId")
            _state.value = _state.value.copy(
                ready = false,
                on = false,
                lastError = "手电筒当前不可用（相机被占用或 LED 过热保护中）"
            )
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId != _state.value.cameraId) return
            Logx.i(TAG, "onTorchStrengthLevelChanged level=$newStrengthLevel")
            _state.value = _state.value.copy(strength = newStrengthLevel, on = newStrengthLevel > 0)
        }
    }

    /** 我们「希望」灯是亮的（用于区分「用户关的」和「系统关的」） */
    @Volatile
    private var requested = false

    /** 注册系统回调，等待 torch 可用性通知。 */
    fun start() {
        registerCallback()
    }

    /** 主动枚举一次闪光灯能力（suspend，内部走 IO 线程）。 */
    suspend fun refresh() = discover()

    private fun registerCallback() {
        if (callbackRegistered) return
        val cm = cameraManager ?: return
        try {
            cm.registerTorchCallback({ r -> r.run() }, torchCallback)
            callbackRegistered = true
        } catch (t: Throwable) {
            Logx.e(TAG, "registerTorchCallback 失败", t)
        }
    }

    fun stop() {
        val cm = cameraManager ?: return
        if (callbackRegistered) {
            try {
                cm.unregisterTorchCallback(torchCallback)
            } catch (t: Throwable) {
                Logx.w(TAG, "unregisterTorchCallback: ${t.message}")
            }
            callbackRegistered = false
        }
    }

    /** 找出带闪光灯的摄像头；优先后置且带闪光灯的那颗。 */
    private suspend fun discover() = withContext(Dispatchers.IO) {
        val cm = cameraManager
        if (cm == null) {
            _state.value = _state.value.copy(lastError = "本机没有 CameraManager（无相机硬件）")
            return@withContext
        }
        try {
            var fallback: String? = null
            var chosen: String? = null
            var maxStrength = 1
            var supportsStrength = false

            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (!flash) continue
                if (fallback == null) fallback = id
                val facing = ch.get(CameraCharacteristics.LENS_FACING)

                val deviceMax: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                } else null

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id
                    maxStrength = deviceMax ?: 1
                    supportsStrength = deviceMax != null && deviceMax > 1
                    break
                }
            }
            if (chosen == null) chosen = fallback
            if (chosen == null) {
                _state.value = _state.value.copy(
                    hasFlash = false, ready = false,
                    lastError = "未找到带闪光灯的摄像头"
                )
                Logx.w(TAG, "no flash unit found")
                return@withContext
            }
            _state.value = _state.value.copy(
                cameraId = chosen,
                hasFlash = true,
                ready = true,
                maxStrength = maxStrength,
                supportsStrengthApi = supportsStrength,
                strength = if (supportsStrength) maxStrength else 1,
                lastError = null,
            )
            Logx.i(TAG, "torch camera=$chosen maxStrength=$maxStrength supportsStrengthApi=$supportsStrength")
        } catch (t: Throwable) {
            Logx.e(TAG, "discover 失败", t)
            _state.value = _state.value.copy(lastError = "枚举相机失败: ${t.message}")
        }
    }

    /**
     * 开灯。level 为 null 时使用最大亮度。
     * @return true 表示已成功下发
     */
    suspend fun turnOn(level: Int? = null): Boolean = mutex.withLock {
        val target = _state.value.cameraId
        val cm = cameraManager
        if (target == null || cm == null) {
            _state.value = _state.value.copy(lastError = "没有可用的闪光灯")
            return false
        }
        requested = true
        withContext(Dispatchers.IO) {
            try {
                val wantStrength = (level ?: _state.value.maxStrength)
                    .coerceIn(1, _state.value.maxStrength.coerceAtLeast(1))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && _state.value.supportsStrengthApi) {
                    turnOnWithStrength(cm, target, wantStrength)
                } else {
                    cm.setTorchMode(target, true)
                }
                _state.value = _state.value.copy(
                    on = true,
                    strength = if (_state.value.supportsStrengthApi) wantStrength else 1,
                    lastError = null,
                )
                Logx.i(TAG, "turnOn ok (strength=${_state.value.strength})")
                true
            } catch (e: CancellationException) {
                requested = false
                throw e
            } catch (t: Throwable) {
                requested = false
                val msg = describe(t)
                Logx.e(TAG, "turnOn 失败", t)
                _state.value = _state.value.copy(on = false, lastError = msg)
                false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun turnOnWithStrength(cm: CameraManager, id: String, level: Int) {
        try {
            cm.turnOnTorchWithStrengthLevel(id, level)
        } catch (e: IllegalArgumentException) {
            // 该机型虽然报了 MAX_LEVEL 但实现拒绝自定义档位
            Logx.w(TAG, "turnOnTorchWithStrengthLevel 被拒绝，回退 setTorchMode: ${e.message}")
            cm.setTorchMode(id, true)
        }
    }

    suspend fun turnOff(): Boolean = mutex.withLock {
        requested = false
        val target = _state.value.cameraId
        val cm = cameraManager
        if (target == null || cm == null) return true
        withContext(Dispatchers.IO) {
            try {
                cm.setTorchMode(target, false)
                _state.value = _state.value.copy(on = false)
                Logx.i(TAG, "turnOff ok")
                true
            } catch (t: Throwable) {
                Logx.w(TAG, "turnOff 失败: ${t.message}")
                // 保守处理：即便抛异常也认为已尝试关闭，避免上层状态机卡死
                _state.value = _state.value.copy(on = false)
                false
            }
        }
    }

    /** 系统温度状态（API 29+），用于上层做占空比降级。 */
    fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return 0
        return try {
            pm.currentThermalStatus
        } catch (t: Throwable) {
            0
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is CameraAccessException -> when (t.reason) {
            CameraAccessException.CAMERA_IN_USE -> "相机被其他应用占用 (CAMERA_IN_USE)"
            CameraAccessException.MAX_CAMERAS_IN_USE -> "相机数量达到上限 (MAX_CAMERAS_IN_USE)：请关掉相机类 App"
            CameraAccessException.CAMERA_DISABLED -> "相机被系统策略禁用 (CAMERA_DISABLED)"
            else -> "相机访问异常 reason=${t.reason}"
        }
        is SecurityException -> "缺少 CAMERA 权限，请在系统设置中授予"
        else -> t.javaClass.simpleName + ": " + (t.message ?: "")
    }

    companion object {
        private const val TAG = "Flashlight"
    }
}
