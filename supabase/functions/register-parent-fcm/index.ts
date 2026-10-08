import type { ParentContext } from "../_shared/auth.ts";
import { requireActiveParentSession } from "../_shared/parent-session.ts";
import {
  type ParentFcmRegistrationInput,
  parentInstallation,
  registerParentFcmAtomic,
} from "../_shared/parent-fcm.ts";
import type { RegisterParentFcmReplyV1 } from "../../../packages/contracts/src/v1/parent-notifications.ts";
import { databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";
import { serveHarbor } from "../_shared/http.ts";
export type RegisterParentFcmDependencies = {
  requireActiveParentSession(
    req: Request,
  ): Promise<ParentContext & { sessionId: string }>;
  registerParentFcmAtomic(
    input: ParentFcmRegistrationInput,
  ): Promise<RegisterParentFcmReplyV1>;
};
export function createRegisterParentFcmHandler(
  deps: RegisterParentFcmDependencies,
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
        return jsonError(
          "VALIDATION_FAILED",
          400,
          "Registration input is invalid",
        );
      }
      const body = value as Record<string, unknown>,
        clientInstallationId = parentInstallation(body.clientInstallationId);
      if (
        !clientInstallationId || typeof body.token !== "string" ||
        !body.token.trim() || body.token.length > 4096 ||
        /[\u0000-\u001f\u007f-\u009f]/u.test(body.token)
      ) {
        return jsonError(
          "VALIDATION_FAILED",
          400,
          "Registration input is invalid",
        );
      }
      return Response.json(
        await deps.registerParentFcmAtomic({
          userId: parent.userId,
          sessionId: parent.sessionId,
          clientInstallationId,
          token: body.token.trim(),
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
  serveHarbor(
    createRegisterParentFcmHandler({
      requireActiveParentSession,
      registerParentFcmAtomic,
    }),
  );
}
