import { serveHarbor } from "../_shared/http.ts";
import {
  updateDeviceDesiredStateAtomic,
  type DesiredStateAtomicInput,
} from "../_shared/clients.ts";
import {
  requireFamilyRole,
  requireParent,
  type FamilyRole,
  type ParentContext,
} from "../_shared/auth.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type UpdateDeviceStateDeps = {
  requireParent(request: Request): Promise<ParentContext>;
  requireFamilyRole(context: ParentContext, familyId: string, roles: FamilyRole[]): Promise<void>;
  updateDesiredState(input: DesiredStateAtomicInput): Promise<{ desiredStateVersion: number }>;
};

function validation(message: string): never {
  throw new HarborAuthError("VALIDATION_FAILED", 400, `VALIDATION_FAILED: ${message}`);
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) validation(`${field} is required`);
  return value.trim();
}

function parseBody(value: unknown) {
  if (!value || typeof value !== "object" || Array.isArray(value)) validation("request body must be an object");
  const body = value as Record<string, unknown>;
  const desiredState = body.desiredState;
  const expectedVersion = body.expectedVersion;

  if (!desiredState || typeof desiredState !== "object" || Array.isArray(desiredState)) {
    validation("desiredState must be an object");
  }
  if (!Number.isSafeInteger(expectedVersion) || Number(expectedVersion) < 0) {
    validation("expectedVersion must be a non-negative integer");
  }

  return {
    deviceId: requiredString(body.deviceId, "deviceId"),
    familyId: requiredString(body.familyId, "familyId"),
    desiredState: desiredState as Record<string, unknown>,
    expectedVersion: Number(expectedVersion),
  };
}

export function createUpdateDeviceStateHandler(dependencies: UpdateDeviceStateDeps) {
  return async (request: Request): Promise<Response> => {
    const context = await dependencies.requireParent(request);
    const input = parseBody(await request.json().catch(() => null));

    await dependencies.requireFamilyRole(context, input.familyId, ["owner", "parent"]);
    const result = await dependencies.updateDesiredState({
      deviceId: input.deviceId,
      familyId: input.familyId,
      actorUserId: context.userId,
      desiredState: input.desiredState,
      expectedVersion: input.expectedVersion,
    });

    return Response.json(result, { status: 200 });
  };
}

export const defaultUpdateDeviceStateDeps: UpdateDeviceStateDeps = {
  requireParent,
  requireFamilyRole,
  updateDesiredState: updateDeviceDesiredStateAtomic,
};

if (import.meta.main) {
  const handler = createUpdateDeviceStateHandler(defaultUpdateDeviceStateDeps);
  serveHarbor(async (request) => {
    try {
      return await handler(request);
    } catch (error) { error = databaseError(error) ?? error;
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("update-device-state failed");
      return new Response(JSON.stringify({ message: "Internal server error" }), {
        status: 500,
        headers: { "content-type": "application/json; charset=utf-8" },
      });
    }
  });
}
