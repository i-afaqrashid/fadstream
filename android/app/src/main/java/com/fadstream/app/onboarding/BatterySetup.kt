package com.fadstream.app.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * "Install once and forget" only works if the OS stops killing the app.
 * Two layers:
 *   1. Standard battery-optimization exemption (official API, works everywhere).
 *   2. OEM auto-start / "protected apps" screens (Xiaomi/Oppo/Vivo/Huawei...) —
 *      these CANNOT be toggled programmatically; we can only deep-link to the
 *      settings screen and tell the user what to tap. See dontkillmyapp.com.
 *
 * Show this flow once during onboarding and re-check on every app resume.
 */
object BatterySetup {

    fun isExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Official "Allow app to run in background?" system dialog. */
    fun requestExemption(ctx: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /**
     * Best-effort deep-link into the manufacturer's autostart/protected-apps
     * screen. Returns true if we could launch one. Components per dontkillmyapp.
     */
    fun openOemAutostart(ctx: Context): Boolean {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            } catch (_: Exception) { /* not this OEM, try next */ }
        }
        return false
    }

    /** True if this device is a known aggressive-killer OEM worth nudging. */
    fun isAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "oppo", "vivo", "huawei", "honor", "samsung", "oneplus")
            .any { m.contains(it) }
    }
}
