# FadStream

[![Build Android APK](https://github.com/I-afaqrashid/fadstream/actions/workflows/android.yml/badge.svg)](https://github.com/I-afaqrashid/fadstream/actions/workflows/android.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Open-source, self-hosted "install once and forget" Android streaming camera.
The phone streams live to **your** server (no long-term local storage on the
device), you control it remotely, and you can record streams on demand straight
to S3.

## 📲 Download the test APK

Grab the latest debug build from the **[Releases page](https://github.com/I-afaqrashid/fadstream/releases/tag/latest)**
(`FadStream-debug.apk`), enable "install from unknown sources", and sideload it.
Every push to `main` rebuilds it on GitHub's runners — see the [Actions tab](https://github.com/I-afaqrashid/fadstream/actions).

> **Ethical use:** the Android app shows a persistent recording indicator and is
> meant to be installed by the device owner on their own device (security cam,
> dashcam, old-phone reuse, baby monitor). It is **not** covert-surveillance
> software, and the server keeps an append-only audit log by design.

## Status

- ✅ **Server core** (this milestone) — media server, control plane, auth,
  recording → S3, dashboard. Bring it up with one command.
- ⏳ **Android app** — next milestone (Kotlin + Jetpack Compose, CameraX,
  foreground service, WHIP primary + SRT fallback, OEM battery onboarding).

## Architecture

```
ANDROID APP            SERVER (this repo)                 BROWSER
 CameraX/MediaCodec --WHIP/WebRTC--> MediaMTX --WHEP--> dashboard live view
   foreground svc     (SRT fallback)   |  \-- records fmp4 --> uploader --> S3/MinIO
   control WSS  <----------------> control-plane (registry, auth, command bus)
                                         |--> Postgres (devices, commands, recordings, audit)
```

## Quick start (local, all in Docker)

```bash
cd deploy
cp .env.example .env          # optional for local; defaults work
docker compose up --build
```

Then:

| What | URL |
|------|-----|
| Dashboard | http://localhost:8081 |
| Control-plane API | http://localhost:8080 |
| MediaMTX WHEP/WHIP | http://localhost:8889 |
| MinIO console | http://localhost:9001 |

### Enroll a device

```bash
curl -X POST localhost:8080/api/devices/register \
  -H 'content-type: application/json' -d '{"name":"garage-cam"}'
# -> { "deviceId": "...", "secret": "...", "streamKey": "..." }
```

### Test ingest without the app yet (ffmpeg → WHIP)

```bash
# Publish a test pattern to the device's path (use the deviceId from above):
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
  -c:v libx264 -tune zerolatency -f whip \
  "http://localhost:8889/devices/<deviceId>/whip?user=<deviceId>&pass=<streamKey>"
```

Open the dashboard, pick the device, and you should see the stream. Hit
**Start recording** → **Stop & save to S3**, then check the recordings table and
the MinIO console.

## Layout

```
deploy/            docker-compose + env
server/
  mediamtx/        media server config (WHIP/WebRTC/SRT/RTMP + recording)
  control-plane/   Fastify: registry, auth, WSS command bus, S3 uploader
  migrations/      Postgres schema
web/               WHEP dashboard (static)
```

## Transport strategy

- **WHIP / WebRTC** — primary. Sub-second latency, encrypted, NAT traversal.
- **SRT** — fallback for lossy/high-latency links (cellular, captive networks).
- **RTMP** — legacy/quick-proof only.

The Android client (next milestone) negotiates WHIP first and falls back to SRT,
with a small in-memory buffer to bridge transport switches so no footage is lost.
