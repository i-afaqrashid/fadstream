package com.fadstream.app.stream

import android.content.Context
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.generic.GenericStream

/**
 * SRT fallback transport (RootEncoder). Used when WHIP/WebRTC can't establish
 * over a very lossy/high-latency link — SRT's ARQ retransmission tolerates loss
 * that breaks WebRTC.
 *
 * IMPORTANT: this owns the camera via its own Camera2Source, so WhipClient must
 * be fully stopped (camera released) before SrtClient.start(), and vice versa —
 * the two transports never run at the same time.
 *
 * Publishes to MediaMTX over SRT using a stream id that carries the path + the
 * per-device credentials, which the control-plane auth hook validates.
 */
class SrtClient(private val context: Context) {

    var onState: ((String) -> Unit)? = null
    private var stream: GenericStream? = null

    fun start(config: DeviceConfig) {
        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) { onState?.invoke("srt:connecting") }
            override fun onConnectionSuccess() { onState?.invoke("srt:connected") }
            override fun onConnectionFailed(reason: String) { onState?.invoke("srt:failed:$reason") }
            override fun onNewBitrate(bitrate: Long) {}
            override fun onDisconnect() { onState?.invoke("srt:disconnected") }
            override fun onAuthError() { onState?.invoke("srt:authError") }
            override fun onAuthSuccess() {}
        }

        // 2-arg constructor defaults to Camera2Source + MicrophoneSource (no preview surface).
        val s = GenericStream(context, checker)
        stream = s

        if (!s.prepareVideo(1280, 720, 1_500_000) || !s.prepareAudio(48000, true, 64_000)) {
            onState?.invoke("srt:prepareFailed")
            return
        }

        // MediaMTX native SRT streamid: "<action>:<path>:<user>:<pass>".
        val streamId =
            "publish:devices/${config.deviceId}:${config.deviceId}:${config.streamKey}"
        val url = "srt://${config.serverHost}:8890?streamid=$streamId"
        Log.i(TAG, "starting SRT to $url")
        s.startStream(url)
    }

    fun switchCamera() {
        try { (stream?.videoSource as? Camera2Source)?.switchCamera() } catch (_: Exception) {}
    }

    fun stop() {
        try { if (stream?.isStreaming == true) stream?.stopStream() } catch (_: Exception) {}
        try { stream?.release() } catch (_: Exception) {}
        stream = null
    }

    companion object { private const val TAG = "SrtClient" }
}
