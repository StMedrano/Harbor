import { registerDeviceFcmAtomic } from "../_shared/clients.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
  type DeviceProofContext,
} from "../_shared/device-proof.ts";
import { HarborAuthError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type RegisterFcmDeps = {
  requireDeviceProof(request: Request): Promise<DeviceProofContext>;
  registerFcm(input: { deviceId: string; token: string }): Promise<void>;
};

function validation(message: string): never {
  throw new HarborAuthError("VALIDATION_FAILED", 400, `VALIDATION_FAILED: ${message}`);
}

function parseToken(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) validation("FCM token is required");
  return value.trim();
}

export function createRegisterFcmHandler(dependencies: RegisterFcmDeps) {
  return async (request: Request): Promise<Response> => {
    const context = await dependencies.requireDeviceProof(request);
    const body = await request.json().catch(() => ({})) as Record<string, unknown>;
    const token = parseToken(body.token);

    await dependencies.registerFcm({ deviceId: context.deviceId, token });
    return new Response(null, { status: 204 });
  };
}

export const defaultRegisterFcmDeps: RegisterFcmDeps = {
  requireDeviceProof: (request) => requireDeviceProof(request, "register-fcm", defaultDeviceProofDependencies),
  registerFcm: async (input) => {
    await registerDeviceFcmAtomic(input);
  },
};

if (import.meta.main) {
  const handler = createRegisterFcmHandler(defaultRegisterFcmDeps);
  Deno.serve(async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("register-fcm failed");
      return jsonError("FORBIDDEN", 500, "Internal server error");
    }
  });
}
