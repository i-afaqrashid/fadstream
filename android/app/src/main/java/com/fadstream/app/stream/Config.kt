package com.fadstream.app.stream

import android.content.Context
import androidx.core.content.edit

/**
 * Device identity + server config, persisted locally.
 * These come from POST /api/devices/register on the server (returned once).
 *
 * NOTE: for production move secret/streamKey into EncryptedSharedPreferences
 * (androidx.security.crypto). Kept plain here for clarity of the skeleton.
 */
data class DeviceConfig(
    val serverHost: String,   // e.g. "10.0.2.2" (emulator->host) or "stream.example.com"
    val deviceId: String,
    val secret: String,
    val streamKey: String,
    // Optional: TURN relay password (username is "fadstream"). Leave blank on
    // LAN; set when deploying to a public server so bad-NAT phones can relay.
    val turnPassword: String = "",
) {
    // MediaMTX WHIP ingest URL for this device's own path.
    fun whipUrl() = "http://$serverHost:8889/devices/$deviceId/whip?user=$deviceId&pass=$streamKey"

    // Control bus + token endpoints on the control-plane (host port 8090).
    fun controlPlane() = "http://$serverHost:8090"
    fun controlWs(token: String) = "ws://$serverHost:8090/ws/device?token=$token"
}

object ConfigStore {
    private const val PREFS = "fadstream"

    fun load(ctx: Context): DeviceConfig? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("deviceId", null) ?: return null
        return DeviceConfig(
            serverHost = p.getString("serverHost", "10.0.2.2")!!,
            deviceId = id,
            secret = p.getString("secret", "")!!,
            streamKey = p.getString("streamKey", "")!!,
            turnPassword = p.getString("turnPassword", "")!!,
        )
    }

    fun save(ctx: Context, c: DeviceConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("serverHost", c.serverHost)
            putString("deviceId", c.deviceId)
            putString("secret", c.secret)
            putString("streamKey", c.streamKey)
        }
    }
}
