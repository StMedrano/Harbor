import { serveHarbor } from "../_shared/http.ts";
import { createRequestClient, claimDeviceAtomic, recordDeviceClaimFailure } from "../_shared/clients.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type DeviceClaimPersistenceInput = { authUserId: string; codeDigest: string; publicKeySpki: string; displayName: string; model: string | null; androidVersion: string | null; supervisionMode: string };
export type DeviceClaimDependencies = {
  requireDeviceIdentity(req: Request): Promise<{ userId: string; accessToken: string }>;
  digestPairingCode(code: string): Promise<string>;
  validateP256Spki(spki: string): Promise<boolean>;
  recordClaimFailure(codeDigest: string): Promise<{ failedAttempts: number; invalidated: boolean }>;
  claimDeviceAtomic(input: DeviceClaimPersistenceInput): Promise<{ deviceId: string; familyId: string; childId: string }>;
};
function bearer(req: Request) { const m = /^Bearer\s+(\S+)$/i.exec(req.headers.get("authorization")?.trim() ?? ""); if (!m) throw new HarborAuthError("AUTH_REQUIRED", 401, "Device authentication is required"); return m[1]; }
async function requireDeviceIdentity(req: Request) {
  const accessToken = bearer(req); const client = createRequestClient(accessToken); const result = await client.auth.getUser(accessToken);
  const user = result.data.user as (typeof result.data.user & { is_anonymous?: boolean }) | null;
  if (result.error || !user) throw new HarborAuthError("AUTH_REQUIRED", 401, "Device authentication is required");
  if (user.is_anonymous !== true) throw new HarborAuthError("FORBIDDEN", 403, "Anonymous child-device identity is required");
  return { userId: user.id, accessToken };
}
async function digest(code: string) {
  const pepper = Deno.env.get("HARBOR_PAIRING_PEPPER")?.trim(); if (!pepper) throw new Error("Missing required environment variable: HARBOR_PAIRING_PEPPER");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(code)));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
async function validateP256Spki(value: string) { try { const bytes = Uint8Array.from(atob(value), (c) => c.charCodeAt(0)); await crypto.subtle.importKey("spki", bytes, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]); return true; } catch { return false; } }
const defaults: DeviceClaimDependencies = { requireDeviceIdentity, digestPairingCode: digest, validateP256Spki, recordClaimFailure: recordDeviceClaimFailure, claimDeviceAtomic };

export async function handleDeviceClaim(req: Request, deps: DeviceClaimDependencies): Promise<Response> {
  try {
    const identity = await deps.requireDeviceIdentity(req);
    let body: Record<string, unknown>; try { body = await req.json(); } catch { return jsonError("VALIDATION_FAILED", 400, "Request body must be valid JSON"); }
    const code = typeof body.code === "string" ? body.code : ""; if (!/^\d{6}$/.test(code)) return jsonError("VALIDATION_FAILED", 400, "Pairing code must be exactly six digits");
    const codeDigest = await deps.digestPairingCode(code); const spki = typeof body.publicKeySpki === "string" ? body.publicKeySpki.trim() : "";
    if (!await deps.validateP256Spki(spki)) { await deps.recordClaimFailure(codeDigest); return jsonError("VALIDATION_FAILED", 400, "A valid P-256 SPKI public key is required"); }
    const device = body.device as Record<string, unknown> | undefined; const displayName = typeof device?.displayName === "string" ? device.displayName.trim() : "";
    const supervisionMode = typeof device?.supervisionMode === "string" ? device.supervisionMode : "unknown";
    if (!displayName || !["unknown", "standard", "full"].includes(supervisionMode)) return jsonError("VALIDATION_FAILED", 400, "Valid device metadata is required");
    const result = await deps.claimDeviceAtomic({ authUserId: identity.userId, codeDigest, publicKeySpki: spki, displayName, model: typeof device?.model === "string" ? device.model : null, androidVersion: typeof device?.androidVersion === "string" ? device.androidVersion : null, supervisionMode });
    return new Response(JSON.stringify(result), { status: 200, headers: { "content-type": "application/json; charset=utf-8" } });
  } catch (error) { error = databaseError(error) ?? error; if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message); throw error; }
}
if (import.meta.main) serveHarbor((req) => handleDeviceClaim(req, defaults));
