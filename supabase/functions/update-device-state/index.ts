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

const CLOCK = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Shape of desiredState.controls, the part of the desired state that Harbor's own apps act on. */
export function validateControls(controls: unknown): void {
  if (controls === undefined) return;
  if (!controls || typeof controls !== "object" || Array.isArray(controls)) validation("controls must be an object");
  const c = controls as Record<string, unknown>;
  const allowed = new Set(["paused", "bedtime", "requireLocation"]);
  for (const key of Object.keys(c)) if (!allowed.has(key)) validation(`unknown control ${key}`);
  if (c.paused !== undefined && typeof c.paused !== "boolean") validation("controls.paused must be a boolean");
  if (c.requireLocation !== undefined && typeof c.requireLocation !== "boolean") validation("controls.requireLocation must be a boolean");
  if (c.bedtime !== undefined) {
    const b = c.bedtime as Record<string, unknown> | null;
    if (!b || typeof b !== "object" || Array.isArray(b)) validation("controls.bedtime must be an object");
    const bed = b as Record<string, unknown>;
    if (typeof bed.enabled !== "boolean") validation("controls.bedtime.enabled must be a boolean");
    if (typeof bed.start !== "string" || !CLOCK.test(bed.start)) validation("controls.bedtime.start must be HH:MM");
    if (typeof bed.end !== "string" || !CLOCK.test(bed.end)) validation("controls.bedtime.end must be HH:MM");
    if (bed.start === bed.end) validation("bedtime start and end must differ");
  }
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

  validateControls((desiredState as Record<string, unknown>).controls);

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
