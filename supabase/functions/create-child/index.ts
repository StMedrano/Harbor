import type {
  ChildV1,
  CreateChildRequestV1,
} from "../../../packages/contracts/src/v1/family.ts";
import { type ParentContext, requireParent } from "../_shared/auth.ts";
import {
  type ChildCreationInput,
  createChildAtomic,
} from "../_shared/child-creation.ts";
import { sha256Hex } from "../_shared/crypto.ts";
import { databaseError } from "../_shared/errors.ts";
import { serveHarbor } from "../_shared/http.ts";
import { jsonError } from "../_shared/responses.ts";
export type CreateChildDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  createChildAtomic(input: ChildCreationInput): Promise<ChildV1>;
};
export function createCreateChildHandler(deps: CreateChildDependencies) {
  return async (request: Request): Promise<Response> => {
    try {
      const parent = await deps.requireParent(request);
      let value: unknown;
      try {
        value = await request.json();
      } catch {
        return jsonError(
          "VALIDATION_FAILED",
          400,
          "Request body must be valid JSON",
        );
      }
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        return jsonError(
          "VALIDATION_FAILED",
          400,
          "Request body must be an object",
        );
      }
      const body = value as Partial<CreateChildRequestV1>;
      if (
        typeof body.familyId !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          body.familyId,
        ) || typeof body.displayName !== "string" ||
        typeof body.idempotencyKey !== "string"
      ) return jsonError("VALIDATION_FAILED", 400, "Child input is invalid");
      const displayName = body.displayName.trim(),
        idempotencyKey = body.idempotencyKey.trim();
      if (
        Array.from(displayName).length < 1 ||
        Array.from(displayName).length > 100 ||
        /[\u0000-\u001f\u007f-\u009f]/u.test(body.displayName) ||
        !idempotencyKey
      ) return jsonError("VALIDATION_FAILED", 400, "Child input is invalid");
      const familyId = body.familyId.toLowerCase();
      const payloadHash = await sha256Hex(
        JSON.stringify({ familyId, displayName }),
      );
      return Response.json(
        await deps.createChildAtomic({
          userId: parent.userId,
          familyId,
          displayName,
          idempotencyKey,
          payloadHash,
        }),
      );
    } catch (error) {
      const known = databaseError(error);
      if (known) return jsonError(known.code, known.status, known.message);
      throw error;
    }
  };
}
if (import.meta.main) {
  serveHarbor(createCreateChildHandler({ requireParent, createChildAtomic }));
}
