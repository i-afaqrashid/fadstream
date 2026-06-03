package com.fadstream.app.stream

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
            ?: enumerator.deviceNames.first()
        val cap = enumerator.createCapturer(name, null)
        capturer = cap
        val src = factory.createVideoSource(false)
        videoSource = src
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        cap.initialize(helper, context, src.capturerObserver)
        cap.startCapture(1280, 720, 30)
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
        pc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc?.setLocalDescription(SdpObserverAdapter(), desc)
                postOffer(config, desc.description)
            }
        }, MediaConstraints())
    }

    private fun postOffer(config: DeviceConfig, sdp: String) {
        val req = Request.Builder()
            .url(config.whipUrl())
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { onState?.invoke("whip:error:${resp.code}"); return }
            val answer = resp.body?.string().orEmpty()
            pc?.setRemoteDescription(
                SdpObserverAdapter(),
                SessionDescription(SessionDescription.Type.ANSWER, answer)
            )
            onState?.invoke("whip:connected")
        }
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
