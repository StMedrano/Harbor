import { requireParent, requireFamilyRole, type ParentContext } from "../_shared/auth.ts";
import { requireRecentAal2 } from "../_shared/aal.ts";

export type RevokeDeviceDeps = {
  requireParent(req: Request): Promise<ParentContext>;
  requireFamilyRole(ctx: ParentContext, familyId: string, roles: Array<"owner" | "parent">): Promise<void>;
  requireRecentAal2(ctx: ParentContext, nowEpochSeconds: number, maxAgeSeconds: number): void;
  revokeDevice(input: { deviceId: string; familyId: string; actorUserId: string }): Promise<void>;
  nowEpochSeconds(): number;
};

export const defaultRevokeDeviceDeps: Partial<RevokeDeviceDeps> = {
  requireParent,
  requireFamilyRole,
  requireRecentAal2,
  nowEpochSeconds: () => Math.floor(Date.now() / 1000),
};

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  if (typeof value !== "string" || value.length === 0) throw new Error("VALIDATION_FAILED");
  return value;
}

export function createRevokeDeviceHandler(deps: RevokeDeviceDeps) {
  return async (req: Request): Promise<Response> => {
    const body = await req.json() as Record<string, unknown>;
    const deviceId = requiredString(body, "deviceId");
    const familyId = requiredString(body, "familyId");
    const parent = await deps.requireParent(req);
    await deps.requireFamilyRole(parent, familyId, ["owner", "parent"]);
    deps.requireRecentAal2(parent, deps.nowEpochSeconds(), 900);
    await deps.revokeDevice({ deviceId, familyId, actorUserId: parent.userId });
    return new Response(null, { status: 204 });
  };
}
