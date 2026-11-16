package com.lumenlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 使用教程。
 *
 * 面向第一次使用的用户，按"准备 → 训练 → 使用 → 排错"四段组织，
 * 每一步只讲必要信息，不解释内部原理（那些放在高级信息里）。
 */
@Composable
fun TutorialScreen(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            // 横向 16dp 与主界面一致；纵向不再额外加 16dp，
            // 统一由下面 Spacer(statusBars) + 16dp 呼吸空间构成。
            .padding(horizontal = 16.dp),
        // 卡片间距 12dp 与主界面一致（原来 16dp 明显更散）
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(16.dp))
        Text(
            "使用教程",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "用手机闪光灯给相机补光。不需要连接相机、不会锁住快门。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TutorialSection(
            number = "1",
            title = "准备工作",
            items = listOf(
                "把手机放在相机旁边，距离 20~30 厘米。",
                "手机麦克风一般在底部或侧面，让它朝向相机快门位置。",
                "取下厚保护壳——它可能挡住麦克风。",
                "相机快门音保持开启（静音快门无法被听到）。",
            ),
        )

        TutorialSection(
            number = "2",
            title = "教会它认识你的快门声（只需一次）",
            items = listOf(
                "打开「设置」，找到「音色训练」。",
                "点「开始采样」。程序会自动适应环境，等待提示变绿。",
                "看到「可以按快门了」后，按 3~5 次相机快门，每次间隔 1 秒以上。",
                "采样时避免把对焦确认声采进去：建议切到手动对焦（MF），或一次按到底。",
                "采样自动结束后，点「训练音色」。",
                "训练报告里会显示频谱形状：如果是左边一堆、右边平掉，说明采到的不是快门，重新采。",
            ),
        )

        TutorialSection(
            number = "3",
            title = "开始使用",
            items = listOf(
                "点「开启补光（常亮）」。",
                "按钮变亮表示已就绪，此时按相机快门，灯会跟着响应。",
                "「常亮」模式适合大多数场景；「脉冲同步」适合慢门静态拍摄。",
            ),
        )

        TutorialSection(
            number = "4",
            title = "遇到问题",
            items = listOf(
                "按快门没反应 → 手机离相机太远，挪近到 20 厘米内；或重新训练音色。",
                "总是误触发 → 环境太吵，或训练时采到了杂音。清除模板后重采，只按快门不出声。",
                "采样采不到 → 展开「高级信息」，看「电平」有没有跳动；没有就检查录音权限。",
                "音色门限太低（误触多）→ 多点几次「训练音色」，样本越多越准。",
            ),
        )

        Spacer(Modifier.height(8.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("明白了，开始使用")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TutorialSection(
    number: String,
    title: String,
    items: List<String>,
    tip: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        number,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items.forEachIndexed { i, line ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = LatinFontFamily,
                        fontWeight = LatinFontWeight,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        // 教程正文里常出现英文型号/缩写（MF、AF、LED），一起换字体
                        latinSpans(line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            tip?.let {
                Text(
                    latinSpans(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x14FFFFFF))
                        .padding(8.dp),
                )
            }
        }
    }
}
