package com.fadstream.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fadstream.app.stream.ConfigStore
import com.fadstream.app.ui.MainActivity

/**
 * Resume streaming after reboot.
 *
 * IMPORTANT: modern Android (12+, enforced hard on 14/16) forbids a foreground
 * service started from the background — which a BOOT_COMPLETED receiver is —
 * from accessing the camera or microphone. So we CANNOT silently re-grab the
 * camera on boot; that's a deliberate privacy protection, not a bug we can code
 * around.
 *
 * The compliant pattern: post a high-priority notification that brings the user
 * to the app with a single tap, which puts the app in the foreground and lets it
 * legally start the camera service again. One tap after a reboot is the best any
 * app can do here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ConfigStore.load(context) == null) return  // not enrolled, nothing to resume

        val channelId = "fadstream_boot"
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Resume after restart", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        // Tapping launches MainActivity with autostart=true -> app is now in the
        // foreground, so it can legally start the camera streaming service.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("autostart", true)
        }
        val pi = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setContentTitle("FadStream — resume after restart")
            .setContentText("Your phone restarted. Tap to resume streaming.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        mgr.notify(2002, notif)
    }
}
