package com.lumenlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 崩溃兜底界面。
 *
 * 为什么需要：用户点"复制诊断"会闪退，闪退后什么信息都带不走。
 * 这个界面在渲染主体失败时接管，把异常和已捕获的日志**直接画在屏幕上**，
 * 让用户用截图就能把关键信息传出来 —— 不依赖剪贴板、不依赖文件、不依赖重启。
 */
@Composable
fun CrashFallbackScreen(
    error: Throwable?,
    onRetry: () -> Unit,
    onGetLog: () -> String,
) {
    val log = remember { runCatching { onGetLog() }.getOrElse { "(日志不可用: ${it.message})" } }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "界面渲染出错",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "下面是错误信息和最近的日志。请直接截图发给我。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        error?.let {
            Text(
                buildString {
                    appendLine("${it.javaClass.name}")
                    appendLine(it.message ?: "(无 message)")
                    appendLine()
                    it.stackTrace.take(12).forEach { f -> appendLine("  at $f") }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试渲染") }

        Text(
            "最近日志",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            val tail = log.lines().takeLast(150)
            if (tail.isEmpty()) {
                Text("(暂无日志)", style = MaterialTheme.typography.bodySmall)
            }
            tail.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Compose 的 remember 轻量包装（避免为了一处引入额外依赖）。 */
@Composable
private inline fun <T> remember(crossinline block: () -> T): T =
    androidx.compose.runtime.remember { block() }
