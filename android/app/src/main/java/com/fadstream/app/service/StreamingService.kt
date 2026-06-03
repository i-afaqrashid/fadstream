package com.fadstream.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.fadstream.app.control.ControlClient
import com.fadstream.app.stream.ConfigStore
import com.fadstream.app.stream.SrtClient
import com.fadstream.app.stream.WhipClient
import kotlin.concurrent.thread

/**
 * The always-on streamer. Runs as a foreground service so Android keeps it
 * alive with the screen off / app "closed". The persistent notification is the
 * required, visible recording indicator.
 *
 * START_STICKY keeps it alive across the app being swept from recents. (After a
 * full device reboot the user reopens the app — Android forbids silently
 * re-acquiring the camera from the background.)
 */
class StreamingService : Service() {

    private var whip: WhipClient? = null
    private var srt: SrtClient? = null
    private var control: ControlClient? = null
    private var callMonitor: CallMonitor? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var hadNetwork = true
    private var wakeLock: PowerManager.WakeLock? = null

    // Transport state. WHIP is primary; after repeated WHIP failures we fall
    // back to SRT (which tolerates lossy links WebRTC can't). "forced" pins a
    // transport chosen from the dashboard (useWhip/useSrt) and disables auto.
    @Volatile private var transport = "whip"
    @Volatile private var forced = false
    @Volatile private var isFront = false   // current camera facing
    private var whipFailures = 0
    private var started = false

    @Volatile private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        // Starting a camera/mic foreground service from the background (e.g. after
        // boot) is blocked by Android 12+. If that happens, stop gracefully
        // instead of crash-looping — the boot notification handles resume.
        try {
            startForegroundWithType()
            foregroundOk = true
            isRunning = true
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If we couldn't go foreground (background camera-FGS restriction), don't
        // retry — bail without restarting.
        if (!foregroundOk) { stopSelf(); return START_NOT_STICKY }
        val config = ConfigStore.load(this) ?: run { stopSelf(); return START_NOT_STICKY }

        if (!started) {
            started = true
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fadstream:stream")
                .also { it.acquire() }

            startStreaming(config)

            // Persistent control channel: remote start/stop/restart + heartbeats.
            control = ControlClient(
                config = config,
                onCommand = ::handleCommand,
                facingProvider = { if (isFront) "front" else "back" },
                transportProvider = { transport },
            ).also { it.connect() }

            // Recover the mic after a phone call ends (telephony owns it during a call).
            callMonitor = CallMonitor(this) {
                updateNotification("call ended — recovering audio")
                stopAllStreams(); startStreaming(config)
            }.also { it.start() }

            registerNetworkMonitor(config)
        }
        return START_STICKY   // restart us if the OS kills the process
    }

    @Volatile private var reconnectGuard = false
    private val WHIP_FAILURES_BEFORE_SRT = 3

    /**
     * Start the active transport (WHIP primary, SRT fallback). Both own the
     * camera, so only one runs at a time — we always stop the other first.
     */
    private fun startStreaming(config: com.fadstream.app.stream.DeviceConfig) {
        if (transport == "srt") startSrt(config) else startWhip(config)
    }

    private fun startWhip(config: com.fadstream.app.stream.DeviceConfig) {
        stopSrt()
        transport = "whip"
        thread(name = "whip-start") {
            try {
                whip = WhipClient(this).apply {
                    onState = { state ->
                        updateNotification("whip $state")
                        if (state == "whip:connected" || state == "ice:COMPLETED") whipFailures = 0
                        when {
                            // Camera lost to another app / disconnected / failed to
                            // open: NOT a network problem, so don't fall back to SRT
                            // (it needs the camera too). Just retry acquiring it.
                            state.startsWith("camera:error") ||
                                state.startsWith("camera:disconnected") ||
                                state.startsWith("camera:frozen") ||
                                state.startsWith("camera:startFailed") ||
                                state.startsWith("camera:none") ->
                                scheduleCameraRecovery(config)
                            // Network/connection failures -> WHIP reconnect / SRT fallback.
                            state == "pc:FAILED" || state == "ice:FAILED" ||
                                state == "ice:DISCONNECTED" || state.startsWith("whip:netError") ||
                                state.startsWith("whip:error") ->
                                onWhipFailure(config)
                        }
                    }
                    init()
                    start(config)
                }
            } catch (e: Throwable) {
                updateNotification("error: ${e.message}")
                onWhipFailure(config)
            }
        }
    }

    /** Count WHIP failures; after a few, fall back to SRT (unless transport is forced). */
    private fun onWhipFailure(config: com.fadstream.app.stream.DeviceConfig) {
        whipFailures++
        if (!forced && whipFailures >= WHIP_FAILURES_BEFORE_SRT) {
            updateNotification("WHIP failing → switching to SRT")
            transport = "srt"
            startSrt(config)
        } else {
            scheduleWhipReconnect(config)
        }
    }

    private fun scheduleWhipReconnect(config: com.fadstream.app.stream.DeviceConfig) {
        if (reconnectGuard) return            // collapse duplicate triggers
        reconnectGuard = true
        thread(name = "whip-reconnect") {
            Thread.sleep(2000)
            try { whip?.stop() } catch (_: Exception) {}
            whip = null
            reconnectGuard = false
            if (transport == "whip") { updateNotification("reconnecting…"); startWhip(config) }
        }
    }

    /**
     * Internet drop & recovery: when connectivity is lost then comes back (Wi-Fi
     * toggles, airplane mode, cellular handoff), re-establish the stream on the
     * new network instead of waiting for blind reconnect timeouts.
     */
    private fun registerNetworkMonitor(config: com.fadstream.app.stream.DeviceConfig) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                hadNetwork = false
                updateNotification("network lost — waiting…")
            }
            override fun onAvailable(network: android.net.Network) {
                if (!hadNetwork) {
                    hadNetwork = true
                    updateNotification("network back — reconnecting")
                    whipFailures = 0
                    // Fresh network is likely fine — prefer the low-latency primary
                    // again (unless the user pinned a transport).
                    if (!forced) transport = "whip"
                    stopAllStreams(); startStreaming(config)
                }
            }
        }
        netCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    @Volatile private var cameraRecovering = false

    /**
     * Camera was lost (another app opened it, disconnected, or failed to open).
     * Keep retrying to re-acquire it every few seconds until it's free again —
     * the camera-open simply fails while another app holds it, then succeeds
     * once that app releases it.
     */
    private fun scheduleCameraRecovery(config: com.fadstream.app.stream.DeviceConfig) {
        if (cameraRecovering) return
        cameraRecovering = true
        thread(name = "camera-recovery") {
            Thread.sleep(4000)
            cameraRecovering = false
            updateNotification("camera busy — retrying…")
            // Restart whichever transport is active (re-acquires the camera).
            stopAllStreams()
            startStreaming(config)
        }
    }

    private fun startSrt(config: com.fadstream.app.stream.DeviceConfig) {
        stopWhip()
        transport = "srt"
        thread(name = "srt-start") {
            try {
                srt = SrtClient(this).apply {
                    onState = { state ->
                        updateNotification("srt $state")
                        if (state.startsWith("srt:failed") || state == "srt:disconnected") {
                            scheduleSrtReconnect(config)
                        }
                    }
                    start(config)
                }
            } catch (e: Throwable) {
                updateNotification("srt error: ${e.message}")
                scheduleSrtReconnect(config)
            }
        }
    }

    @Volatile private var srtReconnecting = false
    private fun scheduleSrtReconnect(config: com.fadstream.app.stream.DeviceConfig) {
        if (srtReconnecting || transport != "srt") return
        srtReconnecting = true
        thread(name = "srt-reconnect") {
            Thread.sleep(3000)
            srtReconnecting = false
            if (transport == "srt") { stopSrt(); startSrt(config) }
        }
    }

    private fun stopWhip() { try { whip?.stop() } catch (_: Exception) {}; whip = null }
    private fun stopSrt() { try { srt?.stop() } catch (_: Exception) {}; srt = null }
    private fun stopAllStreams() { stopWhip(); stopSrt() }

    private fun handleCommand(type: String) {
        val config = ConfigStore.load(this) ?: return
        when (type) {
            "stop" -> stopSelf()
            // Remote start/stop of the STREAM (service keeps running so control
            // bus stays connected and we can start again any time).
            "stopStream" -> { stopAllStreams(); updateNotification("stream stopped (remote)") }
            "startStream" -> { whipFailures = 0; startStreaming(config) }
            "restart" -> { stopAllStreams(); startStreaming(config) }
            "switchCamera" -> { whip?.switchCamera(); srt?.switchCamera(); isFront = !isFront }
            "useWhip" -> { forced = true; transport = "whip"; whipFailures = 0; startWhip(config) }
            "useSrt" -> { forced = true; transport = "srt"; startSrt(config) }
            "useAuto" -> { forced = false; whipFailures = 0; if (transport != "whip") startWhip(config) }
            // startRecording/stopRecording are honored server-side (MediaMTX).
        }
    }

    private fun startForegroundWithType() {
        val channelId = ensureChannel()
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FadStream is recording")
            .setContentText("Streaming to your server")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(state: String) {
        val channelId = ensureChannel()
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FadStream is recording")
            .setContentText("State: $state")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(): String {
        val id = "fadstream_stream"
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, "Streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return id
    }

    override fun onDestroy() {
        isRunning = false
        stopAllStreams()
        callMonitor?.stop()
        netCallback?.let {
            try { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        control?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 1001
        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, StreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StreamingService::class.java))
        }
    }
}
