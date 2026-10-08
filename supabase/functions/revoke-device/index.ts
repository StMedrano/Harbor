import { serveHarbor } from "../_shared/http.ts";
import { requireRecentAal2 } from "../_shared/aal.ts";
import { requireFamilyRole, requireParent, type ParentContext } from "../_shared/auth.ts";
import { revokeDeviceAtomic } from "../_shared/clients.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type RevokeDeviceDeps = {
  requireParent(req: Request): Promise<ParentContext>;
  requireFamilyRole(ctx: ParentContext, familyId: string, roles: Array<"owner" | "parent">): Promise<void>;
  requireRecentAal2(ctx: ParentContext, nowEpochSeconds: number, maxAgeSeconds: number): void;
  revokeDevice(input: { deviceId: string; familyId: string; actorUserId: string }): Promise<void>;
  nowEpochSeconds(): number;
};

export const defaultRevokeDeviceDeps: RevokeDeviceDeps = {
  requireParent,
  requireFamilyRole,
  requireRecentAal2,
  revokeDevice: async (input) => {
    await revokeDeviceAtomic(input);
  },
  nowEpochSeconds: () => Math.floor(Date.now() / 1000),
};

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || !value.trim()) {
    throw new HarborAuthError("VALIDATION_FAILED", 400, `VALIDATION_FAILED: ${key} is required`);
  }
  return value.trim();
}

export function createRevokeDeviceHandler(deps: RevokeDeviceDeps) {
  return async (req: Request): Promise<Response> => {
    const body = await req.json().catch(() => ({})) as Record<string, unknown>;
    const deviceId = requiredString(body, "deviceId");
    const familyId = requiredString(body, "familyId");
    const parent = await deps.requireParent(req);
    await deps.requireFamilyRole(parent, familyId, ["owner", "parent"]);
    deps.requireRecentAal2(parent, deps.nowEpochSeconds(), 900);
    await deps.revokeDevice({ deviceId, familyId, actorUserId: parent.userId });
    return new Response(null, { status: 204 });
  };
}

if (import.meta.main) {
  const handler = createRevokeDeviceHandler(defaultRevokeDeviceDeps);
  serveHarbor(async (request) => {
    try {
      return await handler(request);
    } catch (error) { error = databaseError(error) ?? error;
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("revoke-device failed");
      return new Response(JSON.stringify({ message: "Internal server error" }), {
        status: 500,
        headers: { "content-type": "application/json; charset=utf-8" },
      });
    }
  });
}
