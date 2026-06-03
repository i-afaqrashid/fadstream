package com.fadstream.app.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadstream.app.onboarding.BatterySetup
import com.fadstream.app.service.StreamingService
import com.fadstream.app.stream.ConfigStore
import com.fadstream.app.stream.DeviceConfig

/**
 * Single-screen control panel: enroll (paste server creds), grant permissions,
 * run the background-survival onboarding, then start the always-on streamer.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HomeScreen() } }
    }

    @Composable
    private fun HomeScreen() {
        val ctx = this
        val existing = remember { ConfigStore.load(ctx) }
        // Pre-fill from saved config so it's never accidentally overwritten with
        // defaults (and so the user can see/edit what's stored).
        var host by remember { mutableStateOf(existing?.serverHost ?: "10.0.2.2") }
        var deviceId by remember { mutableStateOf(existing?.deviceId ?: "") }
        var secret by remember { mutableStateOf(existing?.secret ?: "") }
        var streamKey by remember { mutableStateOf(existing?.streamKey ?: "") }
        var status by remember {
            mutableStateOf(
                if (existing != null) "Enrolled: ${existing.deviceId.take(8)}…" else "Not enrolled"
            )
        }

        val perms = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* user responded */ }

        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FadStream", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(host, { host = it }, label = { Text("Server host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deviceId, { deviceId = it }, label = { Text("Device ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("Secret") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(streamKey, { streamKey = it }, label = { Text("Stream key") }, modifier = Modifier.fillMaxWidth())

            Button(onClick = {
                ConfigStore.save(ctx, DeviceConfig(host.trim(), deviceId.trim(), secret.trim(), streamKey.trim()))
                status = "Saved. Grant permissions next."
            }, modifier = Modifier.fillMaxWidth()) { Text("Save enrollment") }

            Button(onClick = {
                perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS))
            }, modifier = Modifier.fillMaxWidth()) { Text("1. Grant camera / mic / notifications") }

            Button(onClick = {
                if (!BatterySetup.isExempt(ctx)) BatterySetup.requestExemption(ctx)
            }, modifier = Modifier.fillMaxWidth()) { Text("2. Allow background (battery)") }

            if (BatterySetup.isAggressiveOem()) {
                Button(onClick = { BatterySetup.openOemAutostart(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("3. Enable auto-start (${android.os.Build.MANUFACTURER})")
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { StreamingService.start(ctx); status = "Streaming started" },
                modifier = Modifier.fillMaxWidth()) { Text("▶ Start streaming") }
        }
    }
}
