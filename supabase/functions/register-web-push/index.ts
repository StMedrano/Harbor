import { requireParent, type ParentContext } from "../_shared/auth.ts";
import { registerParentWebPushAtomic } from "../_shared/clients.ts";
import { HarborAuthError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type RegisterWebPushDeps = {
  requireParent(request: Request): Promise<ParentContext>;
  registerWebPush(input: {
    userId: string;
    clientInstallationId: string;
    endpoint: string;
    p256dh: string;
    auth: string;
  }): Promise<void>;
};

function validation(message: string): never {
  throw new HarborAuthError("VALIDATION_FAILED", 400, `VALIDATION_FAILED: ${message}`);
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== "string" || !value.trim()) validation(`${name} is required`);
  return value.trim();
}

export function createRegisterWebPushHandler(dependencies: RegisterWebPushDeps) {
  return async (request: Request): Promise<Response> => {
    const parent = await dependencies.requireParent(request);
    const body = await request.json().catch(() => ({})) as Record<string, unknown>;
    const keys = body.keys && typeof body.keys === "object" && !Array.isArray(body.keys)
      ? body.keys as Record<string, unknown>
      : {};

    await dependencies.registerWebPush({
      userId: parent.userId,
      clientInstallationId: requiredString(body.clientInstallationId, "clientInstallationId"),
      endpoint: requiredString(body.endpoint, "endpoint"),
      p256dh: requiredString(keys.p256dh, "p256dh"),
      auth: requiredString(keys.auth, "auth"),
    });

    return new Response(null, { status: 204 });
  };
}

export const defaultRegisterWebPushDeps: RegisterWebPushDeps = {
  requireParent,
  registerWebPush: registerParentWebPushAtomic,
};

if (import.meta.main) {
  const handler = createRegisterWebPushHandler(defaultRegisterWebPushDeps);
  Deno.serve(async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("register-web-push failed");
      return jsonError("FORBIDDEN", 500, "Internal server error");
    }
  });
}
