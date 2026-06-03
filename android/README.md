# FadStream — Android app

The "install once and forget" streaming camera. Captures camera + mic, streams
to your MediaMTX server over **WHIP/WebRTC**, runs as a foreground service so it
survives the screen off / app closed / reboot, and is remotely controllable from
the control-plane.

## Build & run

The **server host is baked in at build time** — pass it as a Gradle property so
the app knows where to stream:

```bash
cd android
./gradlew assembleDebug \
  -PfadstreamServerHost=192.168.1.50 \
  -PfadstreamTurnPassword=your-turn-pass
```

Install the APK on a **physical device** (camera + WebRTC are unreliable on
emulators). In CI, these come from the repo variable `FADSTREAM_SERVER_HOST` and
secret `FADSTREAM_TURN_PASSWORD`.

## Zero-friction usage

There's **nothing to type**. Open the app and tap **▶ Start streaming**:

1. It requests camera + mic (and phone-state) permissions.
2. It **auto-enrolls** this device with the server (registers itself, gets its
   own id/secret/stream-key, stores them) — no manual enrollment, no curl.
3. It starts streaming.

The device shows up on the dashboard named after the phone (e.g. `samsung SM-S948B`).

> Some OEMs (Xiaomi/Oppo/Vivo) need a one-time **auto-start** toggle to keep the
> app alive in the background — the app surfaces a button for that screen.
> After a full reboot, just reopen the app (Android forbids silent camera resume).

### Networking notes
- **Android emulator → host machine:** use host `10.0.2.2` (already the default).
- **Real phone → your PC:** use the PC's LAN IP, and set `webrtcAdditionalHosts`
  in `server/mediamtx/mediamtx.yml` to that IP so ICE candidates are reachable.
- **Cellular / strict NAT:** add a TURN server (see commented block in mediamtx.yml).

## Module map

```
service/StreamingService.kt   foreground service: lifecycle, wakelock, notification (= rec indicator)
service/BootReceiver.kt       auto-resume after reboot
stream/WhipClient.kt          WebRTC PeerConnection + WHIP signaling (camera+mic -> server)
stream/Config.kt              device identity + server URLs, persisted
control/ControlClient.kt      WSS command bus: token auth, commands, heartbeats, reconnect
onboarding/BatterySetup.kt    battery exemption + per-OEM autostart deep-links
ui/MainActivity.kt            Compose enroll + onboarding + start screen
```

## Not yet wired (next passes)
- **SRT fallback** (`stream/SrtClient.kt`) when WHIP can't establish — interface
  referenced by the service; implement against a libsrt/JNI or ffmpeg-kit binding.
- Adaptive bitrate from WebRTC stats (step resolution/bitrate before frames drop).
- EncryptedSharedPreferences for secret/streamKey.
- Thermal API backoff for long 24/7 runs.
