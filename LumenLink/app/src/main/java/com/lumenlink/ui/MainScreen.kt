package com.lumenlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lumenlink.BuildConfig
import com.lumenlink.link.AppUiState
import com.lumenlink.link.BystanderState
import com.lumenlink.link.LinkController
import com.lumenlink.link.LinkState
import com.lumenlink.net.BleProbe
import com.lumenlink.link.LinkController.WatchState
import com.lumenlink.sync.LightMode
import com.lumenlink.sync.SyncConfig

@Composable
fun MainScreen(
    state: AppUiState,
    bystander: BystanderState,
    onStartBystander: (LightMode) -> Unit,
    onStopBystander: () -> Unit,
    onBystanderTest: () -> Unit,
    onDenoise: (Boolean) -> Unit,
    onDenoiseStrength: (Float) -> Unit,
    onMicSelfCheck: () -> String,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onToggleMode: (LightMode) -> Unit,
    onUpdateConfig: (SyncConfig) -> Unit,
    onTestPulse: () -> Unit,
    onRequestMic: () -> Unit,
    onShowTutorial: () -> Unit,
    onOpenSettings: () -> Unit,
    hasMicPermission: Boolean = true,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // 状态栏高度：避开挖孔屏/灵动岛，让标题不被摄像头切成两半。
            // 用真实 inset（全面屏下状态栏高度因机型而异），再补一点呼吸空间。
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(TOP_BREATHING_ROOM))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LumenLink",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = LatinFontFamily,
                        fontWeight = LatinFontWeight,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        // 副标题里的 "LED" 也换成 Oxanium，中文保持系统字体
                        latinSpans("把手机 LED 变成相机的补光光源"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 版本号常驻显示：安装后一眼就能确认跑的是不是刚打的那个包，
                    // 不用再去系统设置里翻，避免"明明改了却没生效"的扯皮。
                    Text(
                        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = LatinFontFamily,
                        fontWeight = LatinFontWeight,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.height(36.dp),
                ) { Text("设置", style = MaterialTheme.typography.labelMedium) }
            }
        }

        // 主路径：听快门声 → 补光。
        item {
            BystanderCard(
                state = bystander,
                onStart = onStartBystander,
                onStop = onStopBystander,
                onTest = onBystanderTest,
                onDenoise = onDenoise,
                onDenoiseStrength = onDenoiseStrength,
                onMicSelfCheck = onMicSelfCheck,
                onRequestMic = onRequestMic,
                onShowTutorial = onShowTutorial,
                onOpenSettings = onOpenSettings,
                hasMicPermission = hasMicPermission,
            )
        }

        item { LightCard(state, onArm, onDisarm, onToggleMode, onUpdateConfig, onTestPulse) }
        item { Spacer(Modifier.height(24.dp)) }
        // 全面屏下导航栏（或手势条）会覆盖底部，留出真实高度免得按钮被挡住
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}

/**
 * 顶部留白，在真实状态栏高度之外再补一点呼吸空间。
 *
 * 状态栏本身的高度由 WindowInsets.statusBars 提供（全面屏机型差异很大），
 * 这里只负责"看起来别太挤"的那一点点余量。
 */
private val TOP_BREATHING_ROOM = 16.dp

/** 输入电平条。 */
@Composable
internal fun LevelBar(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 卡片标题。 */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** 细分隔线。 */
@Composable
internal fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surface)
    )
}

// -----------------------------------------------------------------------------

/**
 * 旁听模式卡片 —— 本 App 的主路径。
 *
 * 核心主张：**不需要连接相机**。手机放在相机旁边，听快门声就触发补光。
 * 好处是相机完全不被接管，快门/光圈/ISO 用户自己在机身上调。
 */
@Composable
private fun WaveformBar(wave: FloatArray, label: String) {
    if (wave.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(30.dp),
        )
        Canvas(Modifier.weight(1f).height(22.dp)) {
            val maxV = (wave.maxOrNull() ?: 0f).coerceAtLeast(1e-4f)
            val n = wave.size
            val w = size.width
            val h = size.height
            val bw = w / n
            for (i in 0 until n) {
                val v = (wave[i] / maxV).coerceIn(0f, 1f)
                val bh = (v * h).coerceAtLeast(1f)
                drawRect(
                    color = androidx.compose.ui.graphics.Color(0xFFFFC53D),
                    topLeft = androidx.compose.ui.geometry.Offset(i * bw, (h - bh) / 2f),
                    size = androidx.compose.ui.geometry.Size(
                        (bw - 1f).coerceAtLeast(1f),
                        bh,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BystanderCard(
    state: BystanderState,
    onStart: (LightMode) -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    onDenoise: (Boolean) -> Unit,
    onDenoiseStrength: (Float) -> Unit,
    onMicSelfCheck: () -> String,
    onRequestMic: () -> Unit,
    onShowTutorial: () -> Unit,
    onOpenSettings: () -> Unit,
    hasMicPermission: Boolean = true,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when {
                            !hasMicPermission -> Color(0xFFE5A23B)   // 缺权限：橙色警示
                            state.listening -> Color(0xFF4CAF50)
                            else -> Color(0xFF6B7480)
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                SectionTitle(
                    when {
                        !hasMicPermission -> "没有麦克风权限"
                        state.listening -> "正在听快门声"
                        else -> "已停止"
                    },
                    Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onShowTutorial,
                    modifier = Modifier.height(32.dp),
                ) { Text("教程", style = MaterialTheme.typography.labelSmall) }
            }

            // 缺麦克风权限：这是**硬阻塞**，不给"开始"按钮，直接给授权入口。
            // 以前这里什么都不说，用户点开始只是没反应，根本不知道为什么。
            if (!hasMicPermission) {
                Text(
                    latinSpans(
                        "这个 App 靠麦克风听相机快门声来触发补光，没有麦克风权限就没法工作。\n" +
                            "点下面的按钮授权即可，不录音、不上传，只在界面可见时使用。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRequestMic,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("授予麦克风权限") }
            } else if (!state.timbreTrained) {
                // 未训练音色时直接给按钮，不写解释文字
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("去设置里训练音色") }
            } else if (state.listening) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("停止")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStart(LightMode.CONTINUOUS) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("开始（常亮）") }
                    OutlinedButton(
                        onClick = { onStart(LightMode.PULSE) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("开始（脉冲）") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("已触发", "${state.triggerCount} 次")
                MiniStat("补光", if (state.lightArmed) "已开" else "未开")
                MiniStat("音色", if (state.timbreTrained) "已训练" else "未训练")
            }

            Text(
                "输入电平",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LevelBar(state.level)

            // 降噪：低端设备底噪高时需要
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "降噪",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.denoiseEnabled, onCheckedChange = onDenoise)
            }

            state.message?.let {
                Text(
                    // 提示文案里可能带英文（录音源名、错误码），混排处理
                    latinSpans(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
internal fun MiniStat(label: String, value: String) {
    Column {
        Text(latinSpans(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 值可能是「12 次」「已开」，也可能是「VOICE_RECOGNITION」：数字和英文都换字体
        Text(latinSpans(value), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
private fun LightCard(
    state: AppUiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onToggleMode: (LightMode) -> Unit,
    onUpdateConfig: (SyncConfig) -> Unit,
    onTestPulse: () -> Unit,
) {
    val cfg = state.sync.config
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("补光")

            if (state.sync.armed) {
                Button(
                    onClick = onDisarm,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("关闭补光", style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(
                    onClick = onArm,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("开启补光（常亮）", style = MaterialTheme.typography.titleMedium) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("常亮", state.sync.mode == LightMode.CONTINUOUS, Modifier.weight(1f)) {
                    onToggleMode(LightMode.CONTINUOUS)
                }
                ModeChip("脉冲同步", state.sync.mode == LightMode.PULSE, Modifier.weight(1f)) {
                    onToggleMode(LightMode.PULSE)
                }
            }

            Text(
                latinSpans(
                    "脉冲模式点亮时长 = 快门速度 + ${cfg.tailMillis}ms 余量 = ${cfg.holdMillis()}ms"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SliderRow(
                label = "曝光余量 tail",
                value = cfg.tailMillis.toFloat(),
                range = 0f..80f,
                suffix = "ms",
            ) { v -> onUpdateConfig(cfg.copy(tailMillis = v.toInt())) }

            SliderRow(
                label = "点亮下限（防频闪/保亮度）",
                value = cfg.minHoldMillis.toFloat(),
                range = 5f..200f,
                suffix = "ms",
            ) { v -> onUpdateConfig(cfg.copy(minHoldMillis = v.toInt())) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTestPulse, modifier = Modifier.weight(1f)) {
                    Text("测试一次脉冲")
                }
                Text(
                    if (state.sync.cooling) "冷却中" else if (state.sync.lightOn) "点亮中" else "待命",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.sync.warning?.let {
                Text(
                    latinSpans(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                latinSpans(
                    "物理限制：手机是 LED 连续光源，没有闪光管的微秒级瞬态功率，无法与 1/1000s 机械快门" +
                        "真正「闪」同步。真正可靠的做法是常亮补光；脉冲模式适合快门慢于 1/30s 的静态场景。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // label 也可能中英混排（如「曝光余量 tail」）
            Text(latinSpans(label), style = MaterialTheme.typography.bodySmall)
            // 数值 + 单位（如 "300ms"）：只把数字段换成数字字体
            Text(latinSpans("${value.toInt()}$suffix"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
