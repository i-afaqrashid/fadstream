package com.fadstream.app.service

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Detects phone calls so streaming can recover the microphone afterwards.
 *
 * During a call the telephony stack owns the mic, so WebRTC's audio goes silent
 * (video keeps streaming — see the audio error callbacks in WhipClient). Once the
 * call ends we re-acquire the mic by restarting the stream.
 *
 * onCallEnded fires on the transition back to IDLE after a ringing/active call.
 * Requires READ_PHONE_STATE on Android 12+; degrades to a no-op without it.
 */
class CallMonitor(
    private val context: Context,
    private val onCallEnded: () -> Unit,
) {
    private var tm: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var callback: TelephonyCallback? = null
    private var wasInCall = false

    fun start() {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        tm = telephony
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handle(state)
                }
                callback = cb
                telephony.registerTelephonyCallback(context.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
                }
                legacyListener = l
                @Suppress("DEPRECATION")
                telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: SecurityException) {
            // READ_PHONE_STATE not granted — skip (audio still recovers on stream restart).
        }
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> wasInCall = true
            TelephonyManager.CALL_STATE_IDLE -> if (wasInCall) { wasInCall = false; onCallEnded() }
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {}
    }
}
