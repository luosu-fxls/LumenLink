package com.lumenlink.core

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * 稳定的客户端身份。
 *
 * 为什么必须持久化：[PtpIpTransport] 的 GUID 是相机识别「客户端」的唯一凭据。
 * 尼康 Z 系（含 Z6II）的 Wi-Fi 网络配置文件是**按 GUID 配对**的：
 *  - 配置文件未配对时，任何 GUID 都能连，但相机侧会处于「待配对」；
 *  - 一旦配对成功，**只有那个 GUID** 能再连上，其他 GUID 会在握手阶段被
 *    直接回 Init_Fail(reason=1)。
 *
 * 如果每次启动都随机生成 GUID，就会出现「第一次能连、重启 App 后再也连不上」的诡异现象。
 * 所以 GUID 和友好名都必须落盘，并且**永远不要**在连上相机后再改友好名。
 */
object ClientIdentity {

    private const val PREFS = "lumenlink_identity"
    private const val KEY_GUID = "client_guid"
    private const val KEY_NAME = "friendly_name"

    /** 固定 16 字节 GUID，由 UUID 派生出稳定的字节序。 */
    fun guid(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_GUID, null)
        if (existing != null) {
            return uuidToBytes(UUID.fromString(existing))
        }
        val uuid = UUID.randomUUID()
        prefs.edit { putString(KEY_GUID, uuid.toString()) }
        Logx.i(TAG, "生成新客户端 GUID: $uuid")
        return uuidToBytes(uuid)
    }

    fun currentGuidText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GUID, null) ?: "(尚未生成)"
    }

    fun friendlyName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NAME, null) ?: "LumenLink"
    }

    /**
     * 重命名需要用户明确确认，因为已配对的相机会因此需要重新配对
     * （配对认的是 GUID，友好名本身不影响鉴权，但改名意味着你在相机上看到的设备名变了）。
     */
    fun setFriendlyName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_NAME, name) }
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private const val TAG = "Identity"
}
