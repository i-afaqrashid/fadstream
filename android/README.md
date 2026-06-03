# FadStream — Android app

The "install once and forget" streaming camera. Captures camera + mic, streams
to your MediaMTX server over **WHIP/WebRTC**, runs as a foreground service so it
survives the screen off / app closed / reboot, and is remotely controllable from
the control-plane.

## Build & run

1. Open the `android/` folder in **Android Studio** (Ladybug or newer).
2. Let it sync Gradle (pulls CameraX, libwebrtc, OkHttp).
3. Run on a **physical device** (camera + WebRTC are unreliable on emulators).

> The Gradle **wrapper jar** isn't committed. Android Studio regenerates it on
> first sync, or run `gradle wrapper` once if you have Gradle installed.

## Enroll a device against your server

With the server stack running (`deploy/docker compose up`):

```bash
curl -X POST <server>:8080/api/devices/register \
  -H 'content-type: application/json' -d '{"name":"my-phone"}'
# -> { deviceId, secret, streamKey }
```

In the app, paste **Server host / Device ID / Secret / Stream key**, then:
1. Grant camera / mic / notifications
2. Allow background (battery exemption)
3. (Aggressive OEMs) Enable auto-start
4. ▶ Start streaming

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
