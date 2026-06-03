package com.fadstream.app.stream

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.fadstream.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Device identity + server config. The server host and TURN password come from
 * the build (BuildConfig); the device identity (id/secret/streamKey) is created
 * automatically on first run via auto-enrollment — the user never types anything.
 */
data class DeviceConfig(
    val serverHost: String,
    val deviceId: String,
    val secret: String,
    val streamKey: String,
    val turnPassword: String = BuildConfig.TURN_PASSWORD,
) {
    fun whipUrl() = "http://$serverHost:8889/devices/$deviceId/whip?user=$deviceId&pass=$streamKey"
    fun controlPlane() = "http://$serverHost:8090"
    fun controlWs(token: String) = "ws://$serverHost:8090/ws/device?token=$token"
}

object ConfigStore {
    private const val PREFS = "fadstream"
    private val http = OkHttpClient()

    fun load(ctx: Context): DeviceConfig? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("deviceId", null) ?: return null
        return DeviceConfig(
            serverHost = p.getString("serverHost", BuildConfig.SERVER_HOST)!!,
            deviceId = id,
            secret = p.getString("secret", "")!!,
            streamKey = p.getString("streamKey", "")!!,
        )
    }

    private fun save(ctx: Context, c: DeviceConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("serverHost", c.serverHost)
            putString("deviceId", c.deviceId)
            putString("secret", c.secret)
            putString("streamKey", c.streamKey)
        }
    }

    /**
     * Return the stored config, or auto-enroll with the server (register this
     * device, persist the returned credentials) on first run. Blocking network
     * call — run off the main thread. Returns null if the server is unreachable.
     */
    fun ensureEnrolled(ctx: Context): DeviceConfig? {
        load(ctx)?.let { return it }
        return try {
            val host = BuildConfig.SERVER_HOST
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val body = JSONObject().put("name", name).toString()
            val req = Request.Builder()
                .url("http://$host:8090/api/devices/register")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val j = JSONObject(r.body!!.string())
                val cfg = DeviceConfig(
                    serverHost = host,
                    deviceId = j.getString("deviceId"),
                    secret = j.getString("secret"),
                    streamKey = j.getString("streamKey"),
                )
                save(ctx, cfg)
                cfg
            }
        } catch (e: Exception) {
            null
        }
    }
}
