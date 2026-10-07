import type { ParentContext } from "../_shared/auth.ts";
import { requireActiveParentSession } from "../_shared/parent-session.ts";
import {
  type ParentFcmRemovalInput,
  parentInstallation,
  removeParentFcmAtomic,
} from "../_shared/parent-fcm.ts";
import { databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";
import { serveHarbor } from "../_shared/http.ts";
export type RemoveParentFcmDependencies = {
  requireActiveParentSession(
    req: Request,
  ): Promise<ParentContext & { sessionId: string }>;
  removeParentFcmAtomic(input: ParentFcmRemovalInput): Promise<void>;
};
export function createRemoveParentFcmHandler(
  deps: RemoveParentFcmDependencies,
) {
  return async (request: Request): Promise<Response> => {
    try {
      const parent = await deps.requireActiveParentSession(request);
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
        return jsonError("VALIDATION_FAILED", 400, "Removal input is invalid");
      }
      const clientInstallationId = parentInstallation(
        (value as Record<string, unknown>).clientInstallationId,
      );
      if (!clientInstallationId) {
        return jsonError("VALIDATION_FAILED", 400, "Removal input is invalid");
      }
      await deps.removeParentFcmAtomic({
        userId: parent.userId,
        sessionId: parent.sessionId,
        clientInstallationId,
      });
      return new Response(null, { status: 204 });
    } catch (error) {
      const known = databaseError(error);
      if (known) return jsonError(known.code, known.status, known.message);
      throw error;
    }
  };
}
if (import.meta.main) {
  serveHarbor(
    createRemoveParentFcmHandler({
      requireActiveParentSession,
      removeParentFcmAtomic,
    }),
  );
}
