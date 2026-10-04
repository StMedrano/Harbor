import { HarborAuthError } from "./errors.ts";

export type DeviceSecurityRecord = {
  deviceId: string;
  familyId: string;
  childId: string;
  authUserId: string;
  publicKeySpki: string;
  revokedAt: string | null;
};

export type DeviceProofContext = {
  deviceId: string;
  familyId: string;
  childId: string;
  authUserId: string;
};

export type DeviceProofDependencies = {
  now: () => Date;
  requireDeviceIdentity: (request: Request) => Promise<{ userId: string; accessToken: string }>;
  loadDeviceSecurity: (deviceId: string) => Promise<DeviceSecurityRecord | null>;
  sha256: (body: string) => Promise<string>;
  verifyP256Signature: (publicKeySpki: string, signature: string, canonical: string) => Promise<boolean>;
  claimNonceAtomic: (deviceId: string, nonce: string, timestamp: number) => Promise<boolean>;
};

function forbidden(message: string): never {
  throw new HarborAuthError("FORBIDDEN", 403, message);
}

export async function requireDeviceProof(
  request: Request,
  operation: string,
  dependencies: DeviceProofDependencies,
): Promise<DeviceProofContext> {
  const identity = await dependencies.requireDeviceIdentity(request);
  const deviceId = request.headers.get("X-Harbor-Device-Id");
  const timestampValue = request.headers.get("X-Harbor-Timestamp");
  const nonce = request.headers.get("X-Harbor-Nonce");
  const signature = request.headers.get("X-Harbor-Signature");

  if (!deviceId || !timestampValue || !nonce || !signature) forbidden("device proof headers are required");
  const timestamp = Number(timestampValue);
  if (!Number.isInteger(timestamp)) forbidden("invalid device proof timestamp");

  const nowSeconds = Math.floor(dependencies.now().getTime() / 1000);
  if (Math.abs(nowSeconds - timestamp) > 300) forbidden("device proof timestamp is outside the allowed window");

  const device = await dependencies.loadDeviceSecurity(deviceId);
  if (!device || device.authUserId !== identity.userId || device.deviceId !== deviceId) forbidden("device identity does not match proof");
  if (device.revokedAt) throw new HarborAuthError("DEVICE_REVOKED", 403, "device has been revoked");

  const body = await request.clone().text();
  const bodyHash = await dependencies.sha256(body);
  const canonical = [operation, deviceId, String(timestamp), nonce, bodyHash].join("\n");
  const valid = await dependencies.verifyP256Signature(device.publicKeySpki, signature, canonical);
  if (!valid) forbidden("invalid device signature");

  const claimed = await dependencies.claimNonceAtomic(deviceId, nonce, timestamp);
  if (!claimed) throw new HarborAuthError("REPLAY_REJECTED", 409, "device proof nonce was already used");

  return {
    deviceId: device.deviceId,
    familyId: device.familyId,
    childId: device.childId,
    authUserId: device.authUserId,
  };
}
