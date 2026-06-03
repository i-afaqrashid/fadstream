package com.fadstream.app.stream

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.webrtc.*
import java.util.concurrent.TimeUnit

/**
 * Streams the device camera+mic to MediaMTX over WHIP (WebRTC-HTTP Ingest).
 *
 * Flow:
 *   1. Build a PeerConnection with local camera (Camera2Capturer) + mic tracks.
 *   2. createOffer -> setLocalDescription.
 *   3. POST the SDP offer to the WHIP URL; MediaMTX replies with an SDP answer.
 *   4. setRemoteDescription(answer) -> media flows.
 *
 * SRT fallback lives in SrtClient (used by StreamingService when WHIP can't
 * establish). This class is intentionally just the WHIP path.
 */
class WhipClient(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    var onState: ((String) -> Unit)? = null

    fun init() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    /** Start publishing. Blocking-ish; call from a background thread. */
    fun start(config: DeviceConfig) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Add TURN here for carriers that block direct UDP.
        }

        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE: $s"); onState?.invoke("ice:$s")
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                onState?.invoke("pc:$s")
            }
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver?) {}
        }) ?: error("failed to create PeerConnection")

        addCameraTrack()
        addMicTrack()
        negotiate(config)
    }

    private fun addCameraTrack() {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run { onState?.invoke("camera:none"); return }

        // CameraEventsHandler surfaces failures as callbacks instead of letting a
        // failed open propagate into a native abort (Samsung restricts camera for
        // background processes -> startCapture can fail).
        val cap = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(err: String?) { onState?.invoke("camera:error:$err") }
            override fun onCameraDisconnected() { onState?.invoke("camera:disconnected") }
            override fun onCameraFreezed(msg: String?) { onState?.invoke("camera:frozen:$msg") }
            override fun onCameraOpening(id: String?) {}
            override fun onFirstFrameAvailable() { onState?.invoke("camera:firstFrame") }
            override fun onCameraClosed() {}
        })
        capturer = cap
        val src = factory.createVideoSource(false)
        videoSource = src
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        cap.initialize(helper, context, src.capturerObserver)
        try {
            cap.startCapture(1280, 720, 30)
        } catch (e: Exception) {
            onState?.invoke("camera:startFailed:${e.message}")
            return
        }
        val track = factory.createVideoTrack("video0", src)
        pc?.addTrack(track, listOf("stream0"))
    }

    private fun addMicTrack() {
        val src = factory.createAudioSource(MediaConstraints())
        audioSource = src
        val track = factory.createAudioTrack("audio0", src)
        pc?.addTrack(track, listOf("stream0"))
    }

    private fun negotiate(config: DeviceConfig) {
        // createOffer's callbacks run ON the WebRTC signaling thread. We must NOT
        // block it (no synchronous network I/O) and must not re-enter it — doing
        // so makes libwebrtc's thread-checker abort() the process. So we set the
        // local description here, then hand the HTTP exchange to OkHttp's async
        // dispatcher (its own thread), and apply the answer from there.
        pc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = postOffer(config, desc.description)
                    override fun onSetFailure(error: String?) {
                        onState?.invoke("whip:setLocalFailed:$error")
                    }
                }, desc)
            }
            override fun onCreateFailure(error: String?) {
                onState?.invoke("whip:offerFailed:$error")
            }
        }, MediaConstraints())
    }

    private fun postOffer(config: DeviceConfig, sdp: String) {
        val req = Request.Builder()
            .url(config.whipUrl())
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        // Async: runs on OkHttp's dispatcher thread, never the signaling thread.
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                onState?.invoke("whip:netError:${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val answer = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || answer.isBlank()) {
                        onState?.invoke("whip:error:${resp.code}")
                        return
                    }
                    // setRemoteDescription is thread-safe to call from here; the
                    // SDK marshals it onto the signaling thread internally.
                    pc?.setRemoteDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() { onState?.invoke("whip:connected") }
                        override fun onSetFailure(error: String?) {
                            onState?.invoke("whip:answerFailed:$error")
                        }
                    }, SessionDescription(SessionDescription.Type.ANSWER, answer))
                }
            }
        })
    }

    fun stop() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        pc?.close(); pc = null
    }

    companion object { private const val TAG = "WhipClient" }
}

/** No-op SDP observer so callers only override what they need. */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
