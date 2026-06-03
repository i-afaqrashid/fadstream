// Recordings uploader sidecar.
// MediaMTX writes fragmented-MP4 segments to /recordings/devices/<id>/...
// This watches for finalized segments, uploads them to S3 (MinIO locally),
// indexes them in the control-plane, then deletes the local copy so the
// server keeps "no long-term local footprint" too.
//
// Run as its own process:  node dist/uploader.js
import { readdir, stat, unlink, readFile } from "node:fs/promises";
import { join } from "node:path";
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";

const ROOT = process.env.RECORDINGS_DIR ?? "/recordings";
const BUCKET = process.env.S3_BUCKET ?? "fadstream-recordings";
const CONTROL_PLANE = process.env.CONTROL_PLANE_URL ?? "http://control-plane:8080";
const SETTLE_MS = 90_000; // wait until a segment is older than this (i.e. finalized)

const s3 = new S3Client({
  region: process.env.S3_REGION ?? "us-east-1",
  endpoint: process.env.S3_ENDPOINT ?? "http://minio:9000", // MinIO for local
  forcePathStyle: true, // required for MinIO
  credentials: {
    accessKeyId: process.env.S3_ACCESS_KEY ?? "fadstream",
    secretAccessKey: process.env.S3_SECRET_KEY ?? "change-me-in-prod",
  },
});

async function walk(dir: string): Promise<string[]> {
  const out: string[] = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(p)));
    else if (entry.name.endsWith(".mp4")) out.push(p);
  }
  return out;
}

// Derive devices/<id> -> device id from the path layout.
function deviceIdFromPath(absPath: string): string | null {
  const m = /devices\/([0-9a-f-]{36})\//.exec(absPath);
  return m ? m[1] : null;
}

async function tick() {
  let files: string[];
  try { files = await walk(ROOT); } catch { return; }
  const now = Date.now();

  for (const file of files) {
    const st = await stat(file);
    if (now - st.mtimeMs < SETTLE_MS) continue; // still being written

    const key = file.replace(`${ROOT}/`, "");
    const deviceId = deviceIdFromPath(file);
    try {
      await s3.send(new PutObjectCommand({
        Bucket: BUCKET,
        Key: key,
        Body: await readFile(file),
        ContentType: "video/mp4",
      }));

      if (deviceId) {
        await fetch(`${CONTROL_PLANE}/api/recordings/complete`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            deviceId,
            path: `s3://${BUCKET}/${key}`,
            startedAt: st.birthtime.toISOString(),
            endedAt: st.mtime.toISOString(),
            bytes: st.size,
          }),
        });
      }
      await unlink(file); // remove local copy after successful upload
      console.log(`uploaded ${key} (${st.size} bytes)`);
    } catch (err) {
      console.error(`upload failed for ${key}:`, err);
    }
  }
}

console.log(`uploader watching ${ROOT} -> s3://${BUCKET}`);
setInterval(tick, 30_000);
tick();
