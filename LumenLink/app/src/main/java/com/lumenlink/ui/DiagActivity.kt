package com.lumenlink.ui

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lumenlink.App
import com.lumenlink.core.Logx
import com.lumenlink.ptp.PtpUsbTransport

/**
 * 应急诊断界面：**刻意做得极简**，不依赖任何复杂 UI 组件、不调用会发 PTP 命令的东西。
 *
 * 存在的原因：主界面点"复制诊断"会闪退，导致排查通道完全断掉。
 * 这个界面只用最基础的 Text/Button，把连接状态和最近日志直接画在屏幕上，
 * 让用户用**截图**就能把信息传出来。
 *
 * 入口：MainActivity 顶部标题栏的「应急」小按钮。
 */
class DiagActivity : ComponentActivity() {

    private val usbPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DiagScreen()
            }
        }
    }

    /** 纯文本界面：状态 + 操作 + 日志，全部避免复杂布局。 */
    @Composable
    private fun DiagScreen() {
        var status by remember { mutableStateOf("就绪") }
        var extra by remember { mutableStateOf("") }
        val logs by Logx.lines.collectAsState()
        val link by App.instance.link.uiState.collectAsState()

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "版本 " + com.lumenlink.BuildConfig.VERSION_NAME +
                    " (code " + com.lumenlink.BuildConfig.VERSION_CODE + ")",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("状态: $status", style = MaterialTheme.typography.bodyMedium)
            Text(
                "连接: ${link.linkState} / 属性数 ${link.propsFound} / ${link.linkDetail}",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    status = "请求 USB 连接…"
                    try {
                        App.instance.link.connectUsb()
                        status = "已发起连接，观察下方日志"
                    } catch (t: Throwable) {
                        status = "connectUsb 抛异常: ${t.javaClass.simpleName}: ${t.message}"
                        Logx.e("Diag", "connectUsb 异常", t)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("① 连接 USB 相机") }

            OutlinedButton(
                onClick = {
                    status = "测试释放快门…"
                    try {
                        App.instance.link.triggerCapture()
                        status = "已下发拍摄指令，观察日志"
                    } catch (t: Throwable) {
                        status = "triggerCapture 抛异常: ${t.javaClass.simpleName}: ${t.message}"
                        Logx.e("Diag", "triggerCapture 异常", t)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("② 测试释放快门") }

            OutlinedButton(
                onClick = {
                    status = "读取 USB 端点…"
                    extra = try {
                        App.instance.link.dumpUsbEndpoints()
                    } catch (t: Throwable) {
                        "dumpUsbEndpoints 异常: ${t.javaClass.simpleName}: ${t.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("③ 列出 USB 端点") }

            OutlinedButton(
                onClick = {
                    // 这一步最容易崩：它会真的和相机通信。
                    status = "生成诊断…"
                    extra = try {
                        App.instance.link.diagnostics()
                        status = "诊断生成成功（内容见下）"
                        ""
                    } catch (t: Throwable) {
                        status = "diagnostics() 崩了: ${t.javaClass.simpleName}: ${t.message}"
                        Logx.e("Diag", "diagnostics 异常", t)
                        t.stackTrace.take(10).joinToString("\n") { "  at $it" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("④ 生成诊断（会真的和相机通信）") }

            if (extra.isNotBlank()) {
                Text(extra, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            Text(
                "—— 最近日志（截图这一段即可）——",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val tail = logs.takeLast(120)
            if (tail.isEmpty()) {
                Text("(暂无日志)", style = MaterialTheme.typography.bodySmall)
            }
            tail.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
