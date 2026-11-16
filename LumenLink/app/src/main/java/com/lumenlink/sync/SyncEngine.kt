package com.lumenlink.sync

import com.lumenlink.core.Logx
import com.lumenlink.flash.FlashlightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SyncState(
    val armed: Boolean = false,
    val lightOn: Boolean = false,
    val mode: LightMode = LightMode.CONTINUOUS,
    val config: SyncConfig = SyncConfig(),
    val thermalStatus: Int = 0,
    val cooling: Boolean = false,
    val continuousOnMillis: Long = 0,
    val totalTriggerCount: Int = 0,
    val recentTriggers: List<TriggerRecord> = emptyList(),
    val warning: String? = null,
)

/**
 * 快门同步引擎：把「相机发生了一帧」这件事翻译成「LED 该亮多久」。
 *
 * 设计要点：
 *  - 点亮动作跑在**独立的高优先级单线程**上（不是默认线程池），避免被其他协程排队拖慢几十毫秒；
 *  - 过热保护分三层：机身上报的 ThermalStatus、连续点亮时长硬上限、以及系统单方面关灯的回调；
 *  - 所有 torch 操作串行化，PULSE 模式下新触发可以取消上一次尚未结束的脉冲（连拍场景）。
 */
class SyncEngine(
    private val flash: FlashlightController,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val mutex = Mutex()
    private val pulseDispatcher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "led-pulse").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher()

    private var pulseJob: Job? = null
    private var guardJob: Job? = null
    private var onSince = 0L
    private val cooling = AtomicBoolean(false)

    @Volatile
    private var mode: LightMode = LightMode.CONTINUOUS

    @Volatile
    private var config: SyncConfig = SyncConfig()

    fun updateConfig(newConfig: SyncConfig) {
        config = newConfig
        _state.value = _state.value.copy(config = newConfig)
    }

    fun setMode(newMode: LightMode) {
        mode = newMode
        _state.value = _state.value.copy(mode = newMode)
    }

    /** 进入「待命」：CONTINUOUS 直接常亮；PULSE 保持熄灭等触发。 */
    suspend fun arm() = mutex.withLock {
        if (_state.value.armed) return@withLock
        _state.value = _state.value.copy(armed = true, warning = null)
        when (mode) {
            LightMode.CONTINUOUS -> {
                val ok = flash.turnOn()
                onSince = System.currentTimeMillis()
                _state.value = _state.value.copy(lightOn = ok)
                if (!ok) {
                    _state.value = _state.value.copy(
                        warning = flash.state.value.lastError ?: "开灯失败"
                    )
                }
            }
            LightMode.PULSE -> {
                flash.turnOff()
                _state.value = _state.value.copy(lightOn = false)
            }
        }
        startGuard()
        Logx.i(TAG, "armed mode=$mode")
    }

    suspend fun disarm() = mutex.withLock {
        pulseJob?.cancel()
        pulseJob = null
        guardJob?.cancel()
        guardJob = null
        flash.turnOff()
        accrueOnTime()
        _state.value = _state.value.copy(armed = false, lightOn = false, cooling = false)
        Logx.i(TAG, "disarmed")
    }

    /**
     * 相机发生一帧（快门触发）。由 PTP 事件或音频触发调用。
     * @param source 触发来源，仅用于统计与 UI 展示（不同来源的延迟特性差异很大）
     */
    fun onTrigger(source: TriggerSource) {
        if (!_state.value.armed) return
        if (cooling.get()) {
            Logx.d(TAG, "冷却中，忽略触发")
            return
        }
        val cfg = config
        val hold = cfg.holdMillis()
        val record = TriggerRecord(source, System.currentTimeMillis(), hold, cfg.compensationMillis)

        val prev = _state.value
        _state.value = prev.copy(
            totalTriggerCount = prev.totalTriggerCount + 1,
            recentTriggers = (listOf(record) + prev.recentTriggers).take(8),
        )

        when (mode) {
            LightMode.PULSE -> firePulse(hold)
            LightMode.CONTINUOUS -> Unit // 常亮，无需动作
        }
    }

    /** 手动测试：亮 holdMs 后熄灭（用来目测同步效果/延迟）。 */
    suspend fun testPulse(holdMs: Int = 300) {
        val wasArmed = _state.value.armed
        if (!wasArmed) {
            flash.turnOn()
            delay(holdMs.toLong())
            flash.turnOff()
        } else {
            firePulse(holdMs)
        }
    }

    private fun firePulse(holdMs: Int) {
        pulseJob?.cancel()
        pulseJob = scope.launch(pulseDispatcher) {
            try {
                accrueOnTime()
                onSince = System.currentTimeMillis()
                flash.turnOn()
                _state.value = _state.value.copy(lightOn = true)
                delay(holdMs.toLong())
                flash.turnOff()
                _state.value = _state.value.copy(lightOn = false)
                accrueOnTime()
            } catch (t: kotlinx.coroutines.CancellationException) {
                flash.turnOff()
                throw t
            } catch (t: Throwable) {
                Logx.e(TAG, "脉冲点亮失败", t)
                _state.value = _state.value.copy(warning = "脉冲点亮失败: ${t.message}")
            }
        }
    }

    private fun accrueOnTime() {
        val since = onSince
        val now = System.currentTimeMillis()
        if (since > 0) {
            val delta = now - since
            if (delta in 1..600_000) {
                _state.value = _state.value.copy(
                    continuousOnMillis = _state.value.continuousOnMillis + delta
                )
            }
            onSince = now
        }
    }

    // ---------------- 过热保护 ----------------

    private fun startGuard() {
        guardJob?.cancel()
        guardJob = scope.launch {
            while (_state.value.armed) {
                delay(GUARD_PERIOD_MS)
                val thermal = flash.thermalStatus()
                _state.value = _state.value.copy(thermalStatus = thermal)
                if (!_state.value.armed) break

                // 1) 机身/系统已经严重过热：立刻熄灭并告警（不自动恢复，避免反复烧 LED）
                if (thermal >= THERMAL_SEVERE) {
                    Logx.w(TAG, "thermal=SEVERE，强制关灯")
                    flash.turnOff()
                    _state.value = _state.value.copy(
                        lightOn = false,
                        armed = false,
                        warning = "手机过热（thermal=$thermal），已强制关闭补光。请冷却后再试。"
                    )
                    break
                }

                // 2) 系统单方面关灯（OEM 固件的 LED 过热保护常见行为）
                val fl = flash.state.value
                if (_state.value.mode == LightMode.CONTINUOUS && !fl.on && _state.value.lightOn) {
                    Logx.w(TAG, "检测到系统已关灯，尝试恢复")
                    val ok = flash.turnOn()
                    _state.value = _state.value.copy(
                        lightOn = ok,
                        warning = if (ok) null else (fl.lastError ?: "补光被系统关闭")
                    )
                }

                // 3) 连续点亮时长硬上限：即使机身不上报温度也要执行（LED 结温保护）
                accrueOnTime()
                val onTime = _state.value.continuousOnMillis
                val isContinuous = _state.value.mode == LightMode.CONTINUOUS
                val onLimit = if (thermal >= THERMAL_MODERATE) MAX_HOT_CONTINUOUS_MS else MAX_CONTINUOUS_MS

                if (isContinuous && onTime > onLimit) {
                    // 温度越高冷却越久：热的时候只是"关一会儿再开"是没用的，必须真正降下来
                    val coolDown = if (thermal >= THERMAL_MODERATE) HOT_COOL_DOWN_MS else COOL_DOWN_MS
                    Logx.w(TAG, "连续点亮 ${onTime}ms 超过上限 ${onLimit}ms (thermal=$thermal)，冷却 ${coolDown}ms")
                    cooling.set(true)
                    flash.turnOff()
                    _state.value = _state.value.copy(
                        lightOn = false,
                        cooling = true,
                        warning = "LED 冷却中（${coolDown / 1000.0}s）",
                    )
                    delay(coolDown)
                    _state.value = _state.value.copy(continuousOnMillis = 0)
                    cooling.set(false)
                    if (_state.value.armed && _state.value.mode == LightMode.CONTINUOUS) {
                        val ok = flash.turnOn()
                        _state.value = _state.value.copy(lightOn = ok, cooling = false, warning = null)
                    }
                }
            }
        }
    }

    fun thermalLabel(status: Int): String = when (status) {
        0 -> "正常"
        1 -> "轻热"
        2 -> "中热"
        3 -> "严重"
        4 -> "临界"
        else -> "危急"
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val GUARD_PERIOD_MS = 2_000L
        private const val THERMAL_MODERATE = 2
        private const val THERMAL_SEVERE = 3

        /** 无论温度如何，连续点亮 3 分钟后强制冷却 */
        private const val MAX_CONTINUOUS_MS = 180_000L
        /** 温热状态下，连续点亮 45 秒就冷却一次 */
        private const val MAX_HOT_CONTINUOUS_MS = 45_000L
        private const val COOL_DOWN_MS = 5_000L
        private const val HOT_COOL_DOWN_MS = 3_000L
    }
}
