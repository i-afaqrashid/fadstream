import type { WebSocket } from "ws";
import { query, audit } from "./db.js";

// In-memory registry of live device control sockets, keyed by device id.
// (For multi-instance deployments, back this with Redis pub/sub; the interface
// stays the same.)
const sockets = new Map<string, WebSocket>();

export function attachDevice(deviceId: string, ws: WebSocket): void {
  // Replace any stale socket for this device.
  sockets.get(deviceId)?.close();
  sockets.set(deviceId, ws);
}

export function detachDevice(deviceId: string, ws: WebSocket): void {
  if (sockets.get(deviceId) === ws) sockets.delete(deviceId);
}

export function isOnline(deviceId: string): boolean {
  return sockets.has(deviceId);
}

/**
 * Enqueue a command for a device, deliver immediately if it's connected.
 * Persisted first so it survives a reconnect (delivered on next attach).
 */
export async function sendCommand(
  deviceId: string,
  type: string,
  payload: Record<string, unknown> = {},
  actor = "admin"
): Promise<{ queued: boolean; delivered: boolean }> {
  const { rows } = await query<{ id: string }>(
    `INSERT INTO commands (device_id, type, payload) VALUES ($1, $2, $3) RETURNING id`,
    [deviceId, type, JSON.stringify(payload)]
  );
  const commandId = rows[0].id;
  await audit(actor, `command.${type}`, deviceId, payload);

  const ws = sockets.get(deviceId);
  let delivered = false;
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify({ kind: "command", id: commandId, type, payload }));
    await query(`UPDATE commands SET delivered_at = now() WHERE id = $1`, [commandId]);
    delivered = true;
  }
  return { queued: true, delivered };
}

/** Flush any commands queued while the device was offline. */
export async function flushPending(deviceId: string, ws: WebSocket): Promise<void> {
  const { rows } = await query<{ id: string; type: string; payload: any }>(
    `SELECT id, type, payload FROM commands
     WHERE device_id = $1 AND delivered_at IS NULL ORDER BY id ASC`,
    [deviceId]
  );
  for (const row of rows) {
    ws.send(JSON.stringify({ kind: "command", id: row.id, type: row.type, payload: row.payload }));
    await query(`UPDATE commands SET delivered_at = now() WHERE id = $1`, [row.id]);
  }
}
