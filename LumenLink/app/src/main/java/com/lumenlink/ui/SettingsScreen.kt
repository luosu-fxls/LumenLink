package com.lumenlink.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenlink.R
import com.lumenlink.link.BystanderState
import com.lumenlink.sync.SampleRange

/**
 * 设置页。
 *
 * 把"不常用但需要时可调"的东西集中到这里：音色训练、降噪强度、
 * 界面颜色、麦克风自检。
 *
 * 主界面只保留"开始/停止"，避免第一次用的人被一堆参数吓到。
 */
@Composable
fun SettingsScreen(
    state: BystanderState,
    accentSel: AccentSelection,
    onClose: () -> Unit,
    onSelectAccent: (AccentSelection) -> Unit,
    onSpectrumSampling: (Boolean) -> Unit,
    onAutoCalibrate: () -> String,
    onSampleTarget: (Int) -> Unit,
    onResetLifetime: () -> Unit,
    /** 是否显示「历史统计」卡片。首次引导流程传 false。 */
    showLifetime: Boolean = true,
    onResetTimbre: () -> Unit,
    onDenoise: (Boolean) -> Unit,
    onDenoiseStrength: (Float) -> Unit,
    onMicSelfCheck: () -> String,
    onRequestMic: () -> Unit,
    onShowTutorial: () -> Unit,
    /** 当前是否已获麦克风权限，用于把授权按钮的文案切换成警示态。 */
    hasMicPermission: Boolean = true,
) {
    var report by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    // 清零确认层级：0=不显示 1..5=当前问第几遍
    var confirmLevel by remember { mutableStateOf(0) }

    // 与主界面用完全相同的滚动容器和几何参数：
    //   LazyColumn + fillMaxWidth() + 横向 16dp + spacedBy(12dp)
    // 之前这里是 Column + verticalScroll + spacedBy(14dp)，卡片间距比主界面
    // 多 2dp、且测量/回收策略与 LazyColumn 不同，滚动粘性表现也不一样。
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(SETTINGS_TOP_BREATHING_ROOM))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose, modifier = Modifier.height(36.dp)) { Text("完成") }
        }
        }

        // ---------------- 音色训练 ----------------
        item {
        SettingsCard(title = "音色训练", subtitle = if (state.timbreTrained) "已训练" else "未训练") {
            Text(
                // "App" 和数字用 Oxanium，中文保持系统字体
                latinSpans(
                    "让 App 认识你的相机快门声。只需做一次。\n" +
                        "点「开始采样」→ 等提示变绿 → 按 3~5 次快门 → 点「训练音色」。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 采样注意事项：对焦提示音是机械快门之外最容易被误采的声音，
            // 一旦采进模板，之后每次半按对焦都会触发补光。
            Text(
                latinSpans(
                    "⚠ 请避免采样进对焦确认声。\n" +
                        "采样时半按快门会对焦并「嘀」一声，这个声音会和快门声一起被学进去，" +
                        "之后每次半按对焦都会误触发补光。\n" +
                        "建议：把相机切到手动对焦（MF），或直接一次按到底完成拍摄。"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFC53D),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x14FFC53D))
                    .padding(8.dp),
            )
            if (state.sampling || state.samplePhase.isNotBlank()) {
                Text(
                    state.samplePhase.ifBlank { "准备中…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.samplePhase.contains("可以按快门")) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.secondary,
                )
            }
            if (state.sampling) {
                val pct = (state.sampleCount / state.sampleTarget.toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (pct >= 0.3f) Color(0xFF4CAF50) else Color(0xFFFFC53D))
                    )
                }
                Text(
                    latinSpans("已采 ${state.sampleCount} / ${state.sampleTarget} 条"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            // ---------- 频谱可视化 ----------
            // 采样时把实际采集到的声音形状画出来，用户能一眼判断"采到的是不是快门"
            if (state.sampling) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "实时频谱",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SpectrumBar(state.liveSpectrum, MaterialTheme.colorScheme.secondary)
                    if (state.recentSpectra.isNotEmpty()) {
                        Text(
                            "已采样本的频谱",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.recentSpectra.forEach { spec ->
                            SpectrumBar(spec, MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "低频←                  →高频",
                        style = MaterialTheme.typography.labelSmall,
                        // 纯中文标签，不用数字字体（Oxanium 无中文字形会整行 fallback）
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------- 采样条数 ----------
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "采样条数",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // 「12 条」混排：只把数字换成数字字体
                    latinSpans("${state.sampleTarget} 条"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            androidx.compose.material3.Slider(
                // 先把 Int 夹到合法区间，再转 Float 给 Slider 用
                value = state.sampleTarget
                    .coerceIn(SampleRange.MIN, SampleRange.MAX)
                    .toFloat(),
                onValueChange = { onSampleTarget(it.toInt()) },
                valueRange = SampleRange.MIN.toFloat()..SampleRange.MAX.toFloat(),
                // steps = 可选中间档数量 = 总档数 - 2
                steps = SampleRange.MAX - SampleRange.MIN - 1,
            )
            Text(
                latinSpans(
                    "条数越多模板越稳，但要多按几次快门。建议 ${SampleRange.RECOMMEND_MIN}~" +
                        "${SampleRange.RECOMMEND_MAX} 条。"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSpectrumSampling(!state.sampling) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.sampling) "停止采样" else "开始采样") }
                Button(
                    onClick = {
                        report = try { onAutoCalibrate() } catch (t: Throwable) {
                            "训练失败: ${t.message}"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("训练音色") }
            }
            if (state.timbreTrained) {
                Text(
                    // 中文「音色门限」+ 数字混排，只给数字段套 Oxanium
                    latinSpans("音色门限 %.2f".format(state.timbreGate)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onResetTimbre() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清除模板，重新训练") }
            }
            if (report.isNotBlank()) {
                Text(
                    // 自检结果是中英文混排，数字段才用数字字体
                    latinSpans(report),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }

        // ---------------- 界面颜色 ----------------
        item {
        SettingsCard(title = "界面颜色") {
            Text(
                latinSpans("选预设，或用下面的 RGB 滑块自己调。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 预设色块
            AppAccent.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { accent ->
                        val selected = !accentSel.isCustom() && accent == accentSel.preset
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(64.dp)
                                .clickable { onSelectAccent(AccentSelection(accent, null)) },
                        ) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(accent.color)
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                        else Color.Transparent,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Text("✓", color = Color.Black.copy(alpha = 0.7f))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                accent.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            DividerLine()
            Text(
                latinSpans("自定义颜色（RGB）"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rgb = accentSel.rgb()
            val preview = Color(rgb[0], rgb[1], rgb[2])
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(preview)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "R %d  G %d  B %d".format(rgb[0], rgb[1], rgb[2]),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = LatinFontFamily,
                    fontWeight = LatinFontWeight,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RgbSlider("R", rgb[0], Color(0xFFFF5252)) { v ->
                onSelectAccent(AccentSelection(accentSel.preset, intArrayOf(v, rgb[1], rgb[2])))
            }
            RgbSlider("G", rgb[1], Color(0xFF69F0AE)) { v ->
                onSelectAccent(AccentSelection(accentSel.preset, intArrayOf(rgb[0], v, rgb[2])))
            }
            RgbSlider("B", rgb[2], Color(0xFF4FC3F7)) { v ->
                onSelectAccent(AccentSelection(accentSel.preset, intArrayOf(rgb[0], rgb[1], v)))
            }
        }
        }

        // ---------------- 降噪 ----------------
        item {
        SettingsCard(title = "降噪") {
            Text(
                "本底噪声高的手机建议开启。过大强度会引入金属音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("启用降噪", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = state.denoiseEnabled,
                    onCheckedChange = onDenoise,
                )
            }
            if (state.denoiseEnabled) {
                // 这里不能用 SliderRow：它显示 value.toInt()，而 0..1 的强度
                // 会被截断成 0 或 1，看起来像"条怎么调都是 0"。
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("降噪强度", style = MaterialTheme.typography.bodySmall)
                        Text(
                            // 纯数值，整体套数字字体
                            "%.2f".format(state.denoiseStrength),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = LatinFontFamily,
                            fontWeight = LatinFontWeight,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    androidx.compose.material3.Slider(
                        value = state.denoiseStrength,
                        onValueChange = onDenoiseStrength,
                        valueRange = 0f..1f,
                    )
                }
            }
        }
        }

        // ---------------- 历史统计（仅从主界面进入时显示） ----------------
        // 首次启动的引导流程里不显示：那时用户还没用过，看到"累计 0 次"
        // 只会显得多余，清零按钮更没有意义。
        if (showLifetime) {
            item {
            SettingsCard(title = "历史统计") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "累计触发次数",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "跨重启累计，本次运行的计数不在此处",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${state.lifetimeTriggers}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = LatinFontFamily,
                        fontWeight = LatinFontWeight,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = { confirmLevel = 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清零") }
            }
            }
        }

        // ---------------- 高级 ----------------
        item {
        SettingsCard(title = "高级") {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showAdvanced) "收起" else "展开诊断信息") }
            if (showAdvanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniStat("录音源", state.audioSource.ifBlank { "未启动" })
                    MiniStat("电平", "%.5f".format(state.level))
                    MiniStat("噪声底", "%.5f".format(state.noiseFloor))
                }
                OutlinedButton(
                    onClick = {
                        report = try { onMicSelfCheck() } catch (t: Throwable) { "自检失败: ${t.message}" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("麦克风自检") }
                OutlinedButton(
                    onClick = onRequestMic,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasMicPermission) "重新申请录音权限" else "⚠ 授予麦克风权限") }
            }
            OutlinedButton(
                onClick = onShowTutorial,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看使用教程") }
        }
        }

        // ---------------- 制作者名单 ----------------
        item { AboutCard() }

        // 底部留白，与主界面完全一致：24dp 呼吸 + 导航栏高度
        item { Spacer(Modifier.height(24.dp)) }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }

    // 清零确认：五层递进，一层比一层小，"取消"反而越来越大。
    // 这个交互是刻意设计成"劝退式"的——历史累计删了就回不来，
    // 靠按钮尺寸引导用户别手滑点错。
    if (confirmLevel > 0) {
        ResetConfirmDialog(
            level = confirmLevel,
            lifetime = state.lifetimeTriggers,
            onConfirm = {
                if (confirmLevel >= RESET_CONFIRM_STEPS) {
                    onResetLifetime()
                    confirmLevel = 0
                } else {
                    confirmLevel++
                }
            },
            onCancel = {
                // 取消时"降一级"，层级降到 0 才真正关闭 ——
                // 也就是连取消要按 5 次才能退出，比确认更难。
                confirmLevel--
            },
        )
    }
}

/** 与主界面 TOP_BREATHING_ROOM 同值，两个页面标题的纵向位置才能对齐。 */
private val SETTINGS_TOP_BREATHING_ROOM = 16.dp

/** 清零确认的最大层级。与 [RESET_CONFIRM_TEXTS] 条数一致。 */
private const val RESET_CONFIRM_STEPS = 4

/**
 * 五层递进的确认文案：越往后越"严重"。
 *
 * 注意：这里的条数必须与 [RESET_CONFIRM_STEPS] 一致。
 */
private val RESET_CONFIRM_TEXTS = listOf(
    "确定？",
    "真的确定吗？",
    "不开玩笑？",
    "随便你吧",
)

/**
 * 清零确认弹窗。
 *
 * 设计要点：
 *  - 「确定」按钮**逐层变小**，但保证最小一级仍有足够点击面积（≥48dp 高）
 *  - 「取消」按钮**逐层变大**，引导用户退出去
 *  - 每层文案递进，从普通确认升级到明确警告
 */
@Composable
private fun ResetConfirmDialog(
    level: Int,
    lifetime: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val idx = (level - 1).coerceIn(0, RESET_CONFIRM_STEPS - 1)
    val last = RESET_CONFIRM_STEPS - 1
    val t = idx.toFloat() / last.toFloat()          // 0..1 的进度
    val text = RESET_CONFIRM_TEXTS[idx]
    // 确定按钮：从 1.0 缩到 0.58
    val confirmWeight = 1f - t * 0.42f
    // 取消按钮：从 1.0 涨到 1.6
    val cancelWeight = 1f + t * 0.60f
    // 字号同步缩小，保底 13sp 保证可读
    val confirmFontSize = (16f - t * 3.0f).coerceAtLeast(13f)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "清零历史触发次数",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 这行中文和数字混排：只给数字段套 Oxanium，中文仍走系统字体。
                // 整体套 LatinFontFamily 会让中文逐字 fallback，行内会出现两种基线。
                Text(
                    latinSpans("当前累计：$lifetime 次"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (idx >= 2) Color(0xFFFF7043) else MaterialTheme.colorScheme.onSurface,
                )

                // 确定：逐层变小
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth(confirmWeight)
                        .height((48f).dp),
                ) {
                    Text(
                        if (level >= RESET_CONFIRM_STEPS) "确认清零" else "确定",
                        fontSize = confirmFontSize.sp,
                    )
                }
                // 取消：逐层变大
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth(if (cancelWeight > 1f) 1f else cancelWeight)
                        .height((44f + t * 24f).dp),
                ) {
                    Text(
                        if (level >= RESET_CONFIRM_STEPS) "算了，不清了" else "取消",
                        fontSize = (14f + t * 5f).sp,
                    )
                }
            }
        }
    }
}

/**
 * 频谱柱状图。
 *
 * @param spectrum 24 个频带的相对强度（0..1）
 */
@Composable
private fun SpectrumBar(spectrum: FloatArray, color: Color) {
    if (spectrum.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
        )
        return
    }
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val n = spectrum.size
        val gap = 2f
        val barW = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val v = spectrum[i].coerceIn(0f, 1f)
            // 最低留 2px，让"几乎没有能量"的频带也能看出存在
            val h = (v * size.height).coerceAtLeast(2f)
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (barW + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}

/** 单条 RGB 滑块（0..255）。 */
@Composable
private fun RgbSlider(label: String, value: Int, tint: Color, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = LatinFontFamily,
            fontWeight = LatinFontWeight,
            color = tint,
            modifier = Modifier.width(18.dp),
        )
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = LatinFontFamily,
            fontWeight = LatinFontWeight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            content()
        }
    }
}

/**
 * 界面强调色。
 *
 * 支持两种来源：
 *  - 预设（[AppAccent]）：一键切换，覆盖常用色
 *  - 自定义（[CustomAccent]）：用户用 RGB 三条滑块自己调
 *
 * 只换 accent（按钮、标题、指示点），不动背景和正文——
 * 这样任何主题下可读性都有保证。
 */
enum class AppAccent(val label: String, val colorHex: Long) {
    Amber("琥珀", 0xFFFFC53D),
    Cyan("青蓝", 0xFF4FC3F7),
    Green("翠绿", 0xFF66BB6A),
    Rose("玫红", 0xFFF06292),
    Violet("紫罗兰", 0xFFBA68C8),
    Orange("橘橙", 0xFFFF8A65),
    Mint("薄荷", 0xFF4DD0A6),
    White("素白", 0xFFECEFF1);

    val color: Color get() = Color(colorHex)

    fun rgb(): IntArray = intArrayOf(
        ((colorHex shr 16) and 0xFF).toInt(),
        ((colorHex shr 8) and 0xFF).toInt(),
        (colorHex and 0xFF).toInt(),
    )
}

/** 当前生效的强调色：预设 或 自定义 RGB。 */
data class AccentSelection(
    val preset: AppAccent = AppAccent.Amber,
    /** 非空表示使用自定义 RGB，此时忽略 [preset]。 */
    val customRgb: IntArray? = null,
) {
    fun rgb(): IntArray = customRgb ?: preset.rgb()
    fun isCustom(): Boolean = customRgb != null
}

/** 采样条数偏好持久化。 */
object SamplePrefs {
    private const val FILE = "lumenlink_prefs"
    private const val KEY = "sample_target"

    fun load(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY, 8).coerceIn(5, 20)

    fun save(context: Context, n: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY, n.coerceIn(5, 20)).apply()
    }
}

/** 颜色偏好持久化（预设 + 可选的自定义 RGB）。 */
object AccentPrefs {
    private const val FILE = "lumenlink_prefs"
    private const val KEY_PRESET = "accent"
    private const val KEY_R = "accent_r"
    private const val KEY_G = "accent_g"
    private const val KEY_B = "accent_b"
    private const val KEY_CUSTOM = "accent_custom"

    fun load(context: Context): AccentSelection {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_PRESET, null)
        val preset = AppAccent.entries.firstOrNull { it.name == name } ?: AppAccent.Amber
        val custom = if (sp.getBoolean(KEY_CUSTOM, false)) {
            intArrayOf(
                sp.getInt(KEY_R, 255),
                sp.getInt(KEY_G, 197),
                sp.getInt(KEY_B, 61),
            )
        } else {
            null
        }
        return AccentSelection(preset, custom)
    }

    fun save(context: Context, sel: AccentSelection) {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val e = sp.edit().putString(KEY_PRESET, sel.preset.name)
        val c = sel.customRgb
        if (c != null) {
            e.putBoolean(KEY_CUSTOM, true)
                .putInt(KEY_R, c[0])
                .putInt(KEY_G, c[1])
                .putInt(KEY_B, c[2])
        } else {
            e.putBoolean(KEY_CUSTOM, false)
        }
        e.apply()
    }
}


/**
 * 一位制作者。
 *
 * 头像统一按圆形显示——三张原图尺寸/比例不一（1024、1080、640 见方），
 * 裁成圆形后视觉上才不会参差。
 */
private data class Credit(
    val role: String,
    val name: String,
    val avatarRes: Int,
    /** 附注（比如 QQ 号），没有就留空。 */
    val note: String = "",
)

/** 制作者名单。顺序即显示顺序。 */
private val CREDITS = listOf(
    Credit("主程序", "Deepseek V4.1 Flash", R.drawable.avatar_deepseek),
    Credit("调试及策划", "风玄", R.drawable.avatar_fengxuan, "QQ：1742782203"),
    Credit("策划及后勤", "E=mc²", R.drawable.avatar_emc2),
)

/**
 * 制作者名单卡片。
 *
 * 放在设置页最底部：属于"看一次就够"的信息，不该占用常用区域。
 */
@Composable
private fun AboutCard() {
    SettingsCard(title = "制作者") {
        DividerLine()
        CREDITS.forEach { credit ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(credit.avatarRes),
                    contentDescription = "${credit.name} 的头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        latinSpans(credit.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        credit.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 附注（QQ 号）靠右显示，与头像、名字同处一行，垂直居中
                credit.note.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        latinSpans(note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}