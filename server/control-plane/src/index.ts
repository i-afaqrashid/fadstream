import Fastify from "fastify";
import websocket from "@fastify/websocket";
import bcrypt from "bcryptjs";
import { nanoid } from "nanoid";
import { query, audit } from "./db.js";
import { issueDeviceToken, verifyDeviceToken } from "./auth.js";
import { attachDevice, detachDevice, flushPending, sendCommand, isOnline } from "./bus.js";

const app = Fastify({ logger: true });
await app.register(websocket);

// CORS: the dashboard is served from a different port (8081) than this API
// (8080), so browsers treat it as cross-origin and block fetches without these
// headers. Allow all origins (self-hosted tool on a trusted LAN).
app.addHook("onRequest", (req, reply, done) => {
  reply.header("Access-Control-Allow-Origin", "*");
  reply.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  reply.header("Access-Control-Allow-Headers", "content-type");
  if (req.method === "OPTIONS") { reply.code(204).send(); return; }
  done();
});

// ---------------------------------------------------------------------------
// Device enrollment & auth
// ---------------------------------------------------------------------------

// Enroll a new device. Returns the one-time secret + stream key the APK stores.
app.post("/api/devices/register", async (req, reply) => {
  const { name } = (req.body as { name?: string }) ?? {};
  if (!name) return reply.code(400).send({ error: "name required" });

  const secret = nanoid(40);
  const streamKey = nanoid(24);
  const secretHash = await bcrypt.hash(secret, 10);

  const { rows } = await query<{ id: string }>(
    `INSERT INTO devices (name, secret_hash, stream_key) VALUES ($1, $2, $3) RETURNING id`,
    [name, secretHash, streamKey]
  );
  const id = rows[0].id;
  await audit("admin", "device.register", id, { name });

  // Returned ONCE. The phone persists these in encrypted storage.
  return { deviceId: id, secret, streamKey };
});

// Exchange a device secret for a short-lived JWT (used on the control WS).
app.post("/api/devices/:id/token", async (req, reply) => {
  const { id } = req.params as { id: string };
  const { secret } = (req.body as { secret?: string }) ?? {};
  const { rows } = await query<{ secret_hash: string; revoked: boolean }>(
    `SELECT secret_hash, revoked FROM devices WHERE id = $1`,
    [id]
  );
  if (!rows.length || rows[0].revoked) return reply.code(401).send({ error: "unauthorized" });
  if (!secret || !(await bcrypt.compare(secret, rows[0].secret_hash)))
    return reply.code(401).send({ error: "unauthorized" });
  return { token: issueDeviceToken(id) };
});

// ---------------------------------------------------------------------------
// Admin / dashboard REST
// ---------------------------------------------------------------------------

app.get("/api/devices", async () => {
  const { rows } = await query(
    `SELECT id, name, status, transport, last_seen_at, enrolled_at, revoked FROM devices ORDER BY enrolled_at DESC`
  );
  return rows.map((d: any) => ({ ...d, online: isOnline(d.id) }));
});

// Issue a command to a device (start/stop/restart/setQuality/rotate/...).
app.post("/api/devices/:id/command", async (req, reply) => {
  const { id } = req.params as { id: string };
  const { type, payload } = (req.body as { type?: string; payload?: any }) ?? {};
  if (!type) return reply.code(400).send({ error: "type required" });
  const result = await sendCommand(id, type, payload ?? {});
  return result;
});

// --- On-demand recording control (saves to S3/MinIO on stop) ----------------
// startRecording / stopRecording are just commands the device & recorder honor,
// but we also flip server-side recording so footage is captured even if the
// phone has no local storage at all.
app.post("/api/devices/:id/record/start", async (req, reply) => {
  const { id } = req.params as { id: string };
  await query(`UPDATE devices SET status = 'streaming' WHERE id = $1`, [id]);
  const r = await sendCommand(id, "startRecording", {}, "admin");
  await audit("admin", "record.start", id, {});
  return r;
});

app.post("/api/devices/:id/record/stop", async (req, reply) => {
  const { id } = req.params as { id: string };
  const r = await sendCommand(id, "stopRecording", {}, "admin");
  await audit("admin", "record.stop", id, {});
  return r;
});

app.get("/api/devices/:id/recordings", async (req) => {
  const { id } = req.params as { id: string };
  const { rows } = await query(
    `SELECT id, path, started_at, ended_at, bytes FROM recordings
     WHERE device_id = $1 ORDER BY started_at DESC LIMIT 200`,
    [id]
  );
  return rows;
});

// MediaMTX calls this when a recording segment is finalized (via runOnRecordSegmentComplete
// hook or a sidecar uploader); we index it and the uploader pushes the file to S3.
app.post("/api/recordings/complete", async (req) => {
  const { deviceId, path, startedAt, endedAt, bytes } =
    req.body as { deviceId: string; path: string; startedAt: string; endedAt?: string; bytes?: number };
  await query(
    `INSERT INTO recordings (device_id, path, started_at, ended_at, bytes) VALUES ($1, $2, $3, $4, $5)`,
    [deviceId, path, startedAt, endedAt ?? null, bytes ?? null]
  );
  await audit("mediamtx", "recording.complete", deviceId, { path, bytes });
  return { ok: true };
});

// ---------------------------------------------------------------------------
// MediaMTX auth hook: authorize every publish/read.
// MediaMTX POSTs { user, password, ip, action, path, protocol, ... }.
// 2xx authorizes; anything else denies.
// ---------------------------------------------------------------------------
app.post("/api/mediamtx/auth", async (req, reply) => {
  const body = req.body as {
    user?: string; password?: string; action?: string; path?: string; query?: string;
  };
  const { action, path } = body;

  // For native-auth protocols MediaMTX fills user/password; for WHIP/WHEP it
  // forwards the URL query (e.g. "user=<id>&pass=<key>"). Accept either.
  const q = new URLSearchParams(body.query ?? "");
  const user = body.user || q.get("user") || "";
  const password = body.password || q.get("pass") || q.get("password") || "";

  // Reads (dashboard playback): allow here; gate behind your dashboard auth.
  // In production, validate an admin/viewer token instead of open read.
  if (action === "read") return reply.code(200).send();

  // Publish: path must be devices/<id>, user=device id, password=stream key.
  if (action === "publish") {
    const m = /^devices\/([0-9a-f-]{36})$/.exec(path ?? "");
    if (!m || m[1] !== user) return reply.code(401).send();
    const { rows } = await query<{ stream_key: string; revoked: boolean }>(
      `SELECT stream_key, revoked FROM devices WHERE id = $1`,
      [user]
    );
    if (!rows.length || rows[0].revoked || rows[0].stream_key !== password)
      return reply.code(401).send();
    await query(`UPDATE devices SET status='live', last_seen_at=now() WHERE id=$1`, [user]);
    return reply.code(200).send();
  }

  return reply.code(401).send();
});

// ---------------------------------------------------------------------------
// Device control WebSocket: persistent command bus + heartbeats.
// ---------------------------------------------------------------------------
app.get("/ws/device", { websocket: true }, (socket, req) => {
  const token = new URL(req.url, "http://x").searchParams.get("token");
  const claims = token ? verifyDeviceToken(token) : null;
  if (!claims) {
    socket.send(JSON.stringify({ kind: "error", error: "unauthorized" }));
    socket.close();
    return;
  }
  const deviceId = claims.sub;
  attachDevice(deviceId, socket as any);
  query(`UPDATE devices SET status='live', last_seen_at=now() WHERE id=$1`, [deviceId]);
  audit(`device:${deviceId}`, "control.connect", deviceId);
  flushPending(deviceId, socket as any);

  socket.on("message", async (raw: Buffer) => {
    let msg: any;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.kind === "heartbeat") {
      await query(
        `UPDATE devices SET last_seen_at=now(), status=$2, transport=$3 WHERE id=$1`,
        [deviceId, msg.status ?? "live", msg.transport ?? null]
      );
    } else if (msg.kind === "ack") {
      await query(`UPDATE commands SET acked_at=now() WHERE id=$1`, [msg.id]);
    }
  });

  socket.on("close", () => {
    detachDevice(deviceId, socket as any);
    query(`UPDATE devices SET status='offline' WHERE id=$1`, [deviceId]);
    audit(`device:${deviceId}`, "control.disconnect", deviceId);
  });
});

app.get("/health", async () => ({ ok: true }));

const port = Number(process.env.PORT ?? 8080);
app.listen({ host: "0.0.0.0", port }).then(() => {
  app.log.info(`control-plane listening on :${port}`);
});
