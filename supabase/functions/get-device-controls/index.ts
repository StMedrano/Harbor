import { serveHarbor } from "../_shared/http.ts";
import { listFamilyDeviceStates, type FamilyDeviceState } from "../_shared/clients.ts";
import { requireFamilyRole, requireParent, type FamilyRole, type ParentContext } from "../_shared/auth.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type GetDeviceControlsDeps = {
  requireParent(request: Request): Promise<ParentContext>;
  requireFamilyRole(context: ParentContext, familyId: string, roles: FamilyRole[]): Promise<void>;
  listStates(input: { familyId: string; actorUserId: string }): Promise<FamilyDeviceState[]>;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Parents read the controls (desired state) of every active device in their family. */
export function createGetDeviceControlsHandler(deps: GetDeviceControlsDeps) {
  return async (request: Request): Promise<Response> => {
    const context = await deps.requireParent(request);
    const body = await request.json().catch(() => null);
    const familyId = body && typeof body === "object" ? (body as Record<string, unknown>).familyId : undefined;
    if (typeof familyId !== "string" || !UUID.test(familyId)) {
      return jsonError("VALIDATION_FAILED", 400, "familyId is required");
    }
    await deps.requireFamilyRole(context, familyId, ["owner", "parent"]);
    const devices = await deps.listStates({ familyId, actorUserId: context.userId });
    return Response.json({ devices }, { status: 200 });
  };
}

export const defaultGetDeviceControlsDeps: GetDeviceControlsDeps = {
  requireParent,
  requireFamilyRole,
  listStates: listFamilyDeviceStates,
};

if (import.meta.main) {
  const handler = createGetDeviceControlsHandler(defaultGetDeviceControlsDeps);
  serveHarbor(async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      const known = databaseError(error);
      if (known) return jsonError(known.code, known.status, known.message);
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("get-device-controls failed");
      return Response.json({ message: "Internal server error" }, { status: 500 });
    }
  });
}
