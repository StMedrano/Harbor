import type { DeviceProofContext } from "../_shared/device-proof.ts";

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
