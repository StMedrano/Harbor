import { syncDeviceAtomic } from "../_shared/clients.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
  type DeviceProofContext,
} from "../_shared/device-proof.ts";
import { HarborAuthError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type DeviceSyncInput = {
  acknowledgedDesiredStateVersion?: number;
  appliedCommandIds?: string[];
};

export type DeviceSyncResult = {
  desiredState: Record<string, unknown>;
  desiredStateVersion: number;
  commands: Array<Record<string, unknown>>;
};

export type DeviceSyncDeps = {
  requireDeviceProof(req: Request): Promise<DeviceProofContext>;
  syncDevice(ctx: DeviceProofContext, input: DeviceSyncInput): Promise<DeviceSyncResult>;
};

function parseInput(value: unknown): DeviceSyncInput {
  if (!value || typeof value !== "object") return {};
  const record = value as Record<string, unknown>;
  const input: DeviceSyncInput = {};
  if (typeof record.acknowledgedDesiredStateVersion === "number" && Number.isInteger(record.acknowledgedDesiredStateVersion)) {
    input.acknowledgedDesiredStateVersion = record.acknowledgedDesiredStateVersion;
  }
  if (Array.isArray(record.appliedCommandIds) && record.appliedCommandIds.every((id) => typeof id === "string")) {
    input.appliedCommandIds = record.appliedCommandIds as string[];
  }
  return input;
}

export function createDeviceSyncHandler(deps: DeviceSyncDeps) {
  return async (req: Request): Promise<Response> => {
    const ctx = await deps.requireDeviceProof(req);
    const input = parseInput(await req.json().catch(() => ({})));
    const result = await deps.syncDevice(ctx, input);
    return Response.json(result, { status: 200 });
  };
}

export const defaultDeviceSyncDeps: DeviceSyncDeps = {
  requireDeviceProof: (request) => requireDeviceProof(request, "device-sync", defaultDeviceProofDependencies),
  syncDevice: async (context, input) => {
    const result = await syncDeviceAtomic({
      deviceId: context.deviceId,
      acknowledgedDesiredStateVersion: input.acknowledgedDesiredStateVersion ?? null,
      appliedCommandIds: input.appliedCommandIds ?? [],
    });
    return {
      desiredState: result.desiredState,
      desiredStateVersion: result.desiredStateVersion,
      commands: result.commands as Array<Record<string, unknown>>,
    };
  },
};

if (import.meta.main) {
  const handler = createDeviceSyncHandler(defaultDeviceSyncDeps);
  Deno.serve(async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("device-sync failed");
      return new Response(JSON.stringify({ message: "Internal server error" }), {
        status: 500,
        headers: { "content-type": "application/json; charset=utf-8" },
      });
    }
  });
}
