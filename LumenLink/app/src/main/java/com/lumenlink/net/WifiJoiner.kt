package com.lumenlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import com.lumenlink.core.Logx

/**
 * 尼康相机的 Wi-Fi 连接。
 *
 * 尼康机身（WMU 时代 D3xxx/D5xxx/D7xxx、以及 SnapBridge 的 Z 系列）在 Wi-Fi 模式下会把
 * 自己变成 AP，客户端连上后用 PTP/IP(TCP 15740) 通信。这里提供两条路径：
 *
 *  A. **手动模式（推荐、最稳）**：用户自己在系统 Wi-Fi 设置里连相机热点（SSID 形如
 *     "NIKON_XXXX" / "NIKON Z 6_XXXX"），然后回 App 填 IP（尼康默认多为 192.168.1.1，
 *     部分机型 192.168.0.1）。本 App 直接在默认网络上开 TCP。
 *
 *  B. **代连模式**：用 [WifiNetworkSpecifier] 让系统弹窗代连。注意此时得到的 Network
 *     **不是**默认网络，所以 socket 必须用 network.socketFactory 创建（见 PtpIpTransport），
 *     否则流量会走蜂窝/家用 Wi-Fi，必然连不上。
 */
object WifiJoiner {

    private const val TAG = "WifiJoiner"

    /** 尼康常见网关地址，UI 里作为候选给出。 */
    val COMMON_CAMERA_IPS = listOf("192.168.1.1", "192.168.0.1", "10.0.0.1")

    data class Result(
        val network: Network?,
        val message: String,
    )

    /**
     * 用 NetworkSpecifier 代连相机热点（Android 10+）。
     * 会弹出系统选择框，用户确认后才真正连接。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestCameraNetwork(
        context: Context,
        ssid: String,
        passphrase: String = "",
        onResult: (Result) -> Unit,
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            onResult(Result(null, "系统没有 ConnectivityManager"))
            return
        }

        val builder = WifiNetworkSpecifier.Builder()
        // 用户填的若恰好等于完整 SSID，用精确匹配；否则当前缀匹配更宽容。
        // 注意：不要 setWpa2Passphrase("")，空口令会抛 IllegalArgumentException。
        builder.setSsidPattern(PatternMatcher(ssid, PatternMatcher.PATTERN_PREFIX))
        if (passphrase.isNotBlank()) {
            if (passphrase.length < 8) {
                onResult(Result(null, "Wi-Fi 密码至少 8 位；相机热点通常没有密码，请留空"))
                return
            }
            builder.setWpa2Passphrase(passphrase)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 相机热点没有外网
            .setNetworkSpecifier(builder.build())
            .build()

        var settled = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (settled) return
                settled = true
                val lp = cm.getLinkProperties(network)
                val ip = lp?.linkAddresses
                    ?.mapNotNull { it.address }
                    ?.filterIsInstance<java.net.Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                Logx.i(TAG, "已连接相机热点: $network 本机IP=${ip ?: "-"}")
                onResult(Result(network, "已连接相机热点 ${ip ?: ""}"))
            }

            override fun onUnavailable() {
                if (settled) return
                settled = true
                Logx.w(TAG, "用户取消或连接超时")
                onResult(
                    Result(
                        null,
                        "连接超时或被取消。请确认相机 Wi-Fi 已开启，并在系统弹窗里点「连接」。",
                    )
                )
            }

            override fun onLost(network: Network) {
                Logx.w(TAG, "相机热点连接断开")
            }
        }

        try {
            cm.requestNetwork(request, callback, 30_000)
        } catch (t: Throwable) {
            Logx.e(TAG, "requestNetwork 失败", t)
            onResult(Result(null, "发起连接失败: ${t.message}"))
        }
    }

    /** 当前默认网络是否是 Wi-Fi（仅用于 UI 提示，不参与决策）。 */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
