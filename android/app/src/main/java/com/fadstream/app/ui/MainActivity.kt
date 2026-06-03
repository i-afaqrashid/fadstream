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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadstream.app.BuildConfig
import com.fadstream.app.onboarding.BatterySetup
import com.fadstream.app.service.StreamingService
import com.fadstream.app.stream.ConfigStore
import kotlin.concurrent.thread

/**
 * Zero-friction UI: one button. Tap Start → it requests the permissions it needs,
 * auto-enrolls this device with the server (no typing), and starts streaming.
 * Server config is baked in at build time (BuildConfig.SERVER_HOST).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Home() } }
    }

    @Composable
    private fun Home() {
        val ctx = this
        var streaming by remember { mutableStateOf(StreamingService.isRunning) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(if (streaming) "Streaming" else "Tap to start") }

        val perms = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result[Manifest.permission.CAMERA] == true &&
                result[Manifest.permission.RECORD_AUDIO] == true
            if (!granted) { status = "Camera & microphone are required"; return@rememberLauncherForActivityResult }

            // Ask the OS to keep us alive in the background (forget-and-run).
            if (!BatterySetup.isExempt(ctx)) BatterySetup.requestExemption(ctx)

            busy = true; status = "Setting up…"
            thread {
                val cfg = ConfigStore.ensureEnrolled(ctx)   // auto-register (network)
                runOnUiThread {
                    busy = false
                    if (cfg == null) {
                        status = "Can't reach server (${BuildConfig.SERVER_HOST}). Check it's running."
                    } else {
                        StreamingService.start(ctx)
                        streaming = true
                        status = "Streaming → ${BuildConfig.SERVER_HOST}"
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("FadStream", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(28.dp))

            if (busy) {
                CircularProgressIndicator()
            } else if (!streaming) {
                Button(
                    onClick = {
                        perms.launch(arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_PHONE_STATE,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("▶  Start streaming", style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(
                    onClick = { StreamingService.stop(ctx); streaming = false; status = "Stopped" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("⏹  Stop streaming", style = MaterialTheme.typography.titleMedium) }
            }

            // The one thing Android can't automate: some OEMs (Xiaomi/Oppo/...) need
            // a manual "auto-start" toggle to keep background apps alive.
            if (BatterySetup.isAggressiveOem()) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { BatterySetup.openOemAutostart(ctx) }) {
                    Text("Stops in the background? Enable auto-start (${android.os.Build.MANUFACTURER})")
                }
            }
        }
    }
}
