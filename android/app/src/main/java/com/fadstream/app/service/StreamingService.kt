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
 * START_STICKY + BootReceiver = "install once and forget": survives the app
 * being swept from recents and survives reboot.
 */
class StreamingService : Service() {

    private var whip: WhipClient? = null
    private var srt: SrtClient? = null
    private var control: ControlClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Transport state. WHIP is primary; after repeated WHIP failures we fall
    // back to SRT (which tolerates lossy links WebRTC can't). "forced" pins a
    // transport chosen from the dashboard (useWhip/useSrt) and disables auto.
    @Volatile private var transport = "whip"
    @Volatile private var forced = false
    private var whipFailures = 0
    private var started = false

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            ).also { it.connect() }
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
                        if (state == "pc:FAILED" || state == "ice:FAILED" ||
                            state == "ice:DISCONNECTED" || state.startsWith("whip:netError") ||
                            state.startsWith("whip:error")) {
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

    private fun startSrt(config: com.fadstream.app.stream.DeviceConfig) {
        stopWhip()
        transport = "srt"
        thread(name = "srt-start") {
            try {
                srt = SrtClient(this).apply {
                    onState = { state -> updateNotification("srt $state") }
                    start(config)
                }
            } catch (e: Throwable) {
                updateNotification("srt error: ${e.message}")
            }
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
            "switchCamera" -> { whip?.switchCamera(); srt?.switchCamera() }
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
        stopAllStreams()
        control?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 1001
        fun start(ctx: Context) {
            val i = Intent(ctx, StreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
