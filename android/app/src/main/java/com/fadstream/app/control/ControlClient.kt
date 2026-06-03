package com.fadstream.app.control

import android.util.Log
import com.fadstream.app.stream.DeviceConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Persistent control bus to the control-plane:
 *   1. Exchange device secret -> short-lived JWT.
 *   2. Open a WebSocket to /ws/device.
 *   3. Receive commands (start/stop/restart/setQuality), send heartbeats + acks.
 *
 * Auto-reconnect with backoff so "install once and forget" survives drops.
 */
class ControlClient(
    private val config: DeviceConfig,
    private val onCommand: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)   // keep the socket warm through Doze
        .build()
    private var ws: WebSocket? = null
    private var closed = false
    private var backoffMs = 1000L

    fun connect() {
        val token = fetchToken() ?: run { scheduleReconnect(); return }
        val req = Request.Builder().url(config.controlWs(token)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                sendHeartbeat("live")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                if (msg.optString("kind") == "command") {
                    val type = msg.optString("type")
                    Log.i(TAG, "command: $type")
                    onCommand(type)
                    // ack so the server marks it delivered+handled
                    webSocket.send(JSONObject().put("kind", "ack").put("id", msg.opt("id")).toString())
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
                Log.w(TAG, "control ws failed: ${t.message}")
                if (!closed) scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) scheduleReconnect()
            }
        })
    }

    fun sendHeartbeat(status: String, transport: String = "whip") {
        ws?.send(
            JSONObject().put("kind", "heartbeat")
                .put("status", status).put("transport", transport).toString()
        )
    }

    private fun fetchToken(): String? = try {
        val body = JSONObject().put("secret", config.secret).toString()
        val req = Request.Builder()
            .url("${config.controlPlane()}/api/devices/${config.deviceId}/token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body!!.string()).optString("token") else null
        }
    } catch (e: Exception) { Log.w(TAG, "token fetch failed: ${e.message}"); null }

    private fun scheduleReconnect() {
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)   // cap at 30s
        Thread {
            Thread.sleep(delay)
            if (!closed) connect()
        }.start()
    }

    fun close() { closed = true; ws?.close(1000, "bye") }

    companion object { private const val TAG = "ControlClient" }
}
