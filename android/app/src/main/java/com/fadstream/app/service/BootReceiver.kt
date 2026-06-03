package com.fadstream.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fadstream.app.stream.ConfigStore

/** Resume streaming automatically after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only auto-start if the device was already enrolled.
            if (ConfigStore.load(context) != null) {
                StreamingService.start(context)
            }
        }
    }
}
