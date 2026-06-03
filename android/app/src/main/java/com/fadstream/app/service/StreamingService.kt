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
    private var control: ControlClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = ConfigStore.load(this) ?: run { stopSelf(); return START_NOT_STICKY }

        if (whip == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fadstream:stream")
                .also { it.acquire() }

            thread(name = "whip-start") {
                try {
                    whip = WhipClient(this).apply {
                        onState = { updateNotification(it) }
                        init()
                        start(config)
                    }
                } catch (e: Throwable) {
                    updateNotification("error: ${e.message}")
                }
            }

            // Persistent control channel: remote start/stop/restart + heartbeats.
            control = ControlClient(
                config = config,
                onCommand = ::handleCommand,
            ).also { it.connect() }
        }
        return START_STICKY   // restart us if the OS kills the process
    }

    private fun handleCommand(type: String) {
        when (type) {
            "stop" -> stopSelf()
            "restart" -> { whip?.stop(); whip = null; onStartCommand(null, 0, 0) }
            // startRecording/stopRecording are honored server-side (MediaMTX);
            // hook here if you also want on-device behavior.
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
        whip?.stop()
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
