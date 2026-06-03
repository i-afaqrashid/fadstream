import jwt from "jsonwebtoken";

const SECRET = process.env.JWT_SECRET || "dev-secret-change-me";

export interface DeviceClaims {
  sub: string; // device id
  role: "device";
}

export function issueDeviceToken(deviceId: string): string {
  const claims: DeviceClaims = { sub: deviceId, role: "device" };
  // Short-lived: the device refreshes via its per-device secret.
  return jwt.sign(claims, SECRET, { expiresIn: "24h" });
}

export function verifyDeviceToken(token: string): DeviceClaims | null {
  try {
    const decoded = jwt.verify(token, SECRET) as DeviceClaims;
    return decoded.role === "device" ? decoded : null;
  } catch {
    return null;
  }
}
