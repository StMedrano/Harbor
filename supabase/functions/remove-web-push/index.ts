import { serveHarbor } from "../_shared/http.ts";
import { requireParent, type ParentContext } from "../_shared/auth.ts";
import { removeParentWebPushAtomic } from "../_shared/clients.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type RemoveWebPushDeps = {
  requireParent(request: Request): Promise<ParentContext>;
  removeWebPush(input: {
    userId: string;
    clientInstallationId: string;
    endpoint?: string;
  }): Promise<void>;
};

function validation(message: string): never {
  throw new HarborAuthError("VALIDATION_FAILED", 400, `VALIDATION_FAILED: ${message}`);
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== "string" || !value.trim()) validation(`${name} is required`);
  return value.trim();
}

export function createRemoveWebPushHandler(dependencies: RemoveWebPushDeps) {
  return async (request: Request): Promise<Response> => {
    const parent = await dependencies.requireParent(request);
    const body = await request.json().catch(() => ({})) as Record<string, unknown>;
    const endpoint = body.endpoint === undefined
      ? undefined
      : requiredString(body.endpoint, "endpoint");

    await dependencies.removeWebPush({
      userId: parent.userId,
      clientInstallationId: requiredString(body.clientInstallationId, "clientInstallationId"),
      endpoint,
    });

    return new Response(null, { status: 204 });
  };
}

export const defaultRemoveWebPushDeps: RemoveWebPushDeps = {
  requireParent,
  removeWebPush: removeParentWebPushAtomic,
};

if (import.meta.main) {
  const handler = createRemoveWebPushHandler(defaultRemoveWebPushDeps);
  serveHarbor(async (request) => {
    try {
      return await handler(request);
    } catch (error) { error = databaseError(error) ?? error;
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("remove-web-push failed");
      return jsonError("FORBIDDEN", 500, "Internal server error");
    }
  });
}
