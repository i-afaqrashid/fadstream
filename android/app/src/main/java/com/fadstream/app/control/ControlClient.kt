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
        // fetchToken() does a blocking HTTP call, so it must never run on the
        // main thread (NetworkOnMainThreadException). Always connect on a worker.
        Log.i(TAG, "connect() invoked")
        Thread { connectBlocking() }.start()
    }

    private fun connectBlocking() {
        Log.i(TAG, "connectBlocking: fetching token from ${config.controlPlane()}")
        val token = fetchToken() ?: run { Log.w(TAG, "no token, will retry"); scheduleReconnect(); return }
        Log.i(TAG, "got token, opening WS")
        val req = Request.Builder().url(config.controlWs(token)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                startHeartbeatLoop(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // A handler exception must NOT bubble up and tear down the socket.
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("kind") == "command") {
                        val type = msg.optString("type")
                        Log.i(TAG, "command: $type")
                        // ack first so delivery is recorded even if the handler is slow
                        webSocket.send(JSONObject().put("kind", "ack").put("id", msg.opt("id")).toString())
                        onCommand(type)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onMessage error: ${e.message}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
                Log.w(TAG, "control ws failed: ${t.message}")
                heartbeatRunning = false
                if (!closed) scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatRunning = false
                if (!closed) scheduleReconnect()
            }
        })
    }

    @Volatile private var heartbeatRunning = false

    /**
     * Send a heartbeat every 15s while the socket is open. Without continuous
     * traffic the connection can be reaped by NAT/proxies/Doze, which is what
     * caused the control channel to die seconds after connecting.
     */
    private fun startHeartbeatLoop(webSocket: WebSocket) {
        heartbeatRunning = true
        Thread {
            while (heartbeatRunning && !closed) {
                val ok = webSocket.send(
                    JSONObject().put("kind", "heartbeat")
                        .put("status", "live").put("transport", "whip").toString()
                )
                if (!ok) break          // socket no longer writable
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "control-heartbeat" }.start()
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
            val bodyStr = r.body?.string().orEmpty()
            Log.i(TAG, "token resp: code=${r.code} bodyLen=${bodyStr.length} body=${bodyStr.take(80)}")
            if (r.isSuccessful) {
                val tok = JSONObject(bodyStr).optString("token")
                tok.ifBlank { null }
            } else null
        }
    } catch (e: Exception) { Log.w(TAG, "token fetch failed: ${e.javaClass.simpleName}: ${e.message}"); null }

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
