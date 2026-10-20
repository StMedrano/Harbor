import {
  ACTIVITY_MAX_BYTES,
  parseActivityReport,
  validateActivityCheckpointRequest,
  validateActivityReadRequest,
  validateClearActivity,
} from "../../../packages/contracts/src/v1/activity.ts";
import { validateDeviceControlsRequest } from "../../../packages/contracts/src/v1/controls.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  ActivityCheckpointReplyV1,
  ActivityReadReplyV1,
  ActivityReadRequestV1,
  ActivityReportV1,
  ActivityWriteReplyV1,
  ClearActivityV1,
} from "../../../packages/contracts/src/v1/activity.ts";
import type { DeviceControlsReplyV1 } from "../../../packages/contracts/src/v1/controls.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type ActivityEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  writeActivity(
    ctx: DeviceProofContext,
    report: ActivityReportV1,
    hash: string,
  ): Promise<ActivityWriteReplyV1>;
  clearActivity(
    ctx: DeviceProofContext,
    clear: ClearActivityV1,
    hash: string,
  ): Promise<ActivityWriteReplyV1>;
  readActivity(
    parent: ParentContext,
    deviceId: string,
    window: ActivityReadRequestV1,
  ): Promise<ActivityReadReplyV1>;
  readActivityCheckpoint(
    ctx: DeviceProofContext,
  ): Promise<ActivityCheckpointReplyV1>;
  readControls(
    parent: ParentContext,
    deviceId: string,
  ): Promise<DeviceControlsReplyV1>;
};
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, ACTIVITY_MAX_BYTES, "Activity report is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Activity request input is invalid");
async function currentParent(
  deps: ActivityEndpointDependencies,
  request: Request,
) {
  const parent = await deps.requireParent(request);
  if (!parent.sessionId || !uuid.test(parent.sessionId)) {
    throw new HarborAuthError(
      "AUTH_REQUIRED",
      401,
      "Current parent session is required",
    );
  }
  return parent;
}
export function createReportActivityHandler(deps: ActivityEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "report-device-activity");
    const report = parseActivityReport(raw, deps.now());
    return deps.writeActivity(ctx, report, await deps.sha256(raw));
  });
}
export function createClearActivityHandler(deps: ActivityEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "clear-device-activity");
    const clear = validateClearActivity(JSON.parse(raw));
    return deps.clearActivity(ctx, clear, await deps.sha256(raw));
  });
}
export function createActivityCheckpointHandler(
  deps: ActivityEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(
      request,
      "get-device-activity-checkpoint",
    );
    validateActivityCheckpointRequest(JSON.parse(raw));
    return deps.readActivityCheckpoint(ctx);
  });
}
export function createGetActivityHandler(deps: ActivityEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    const value = JSON.parse(raw);
    if (
      !value || typeof value !== "object" || Array.isArray(value) ||
      typeof value.deviceId !== "string" || !uuid.test(value.deviceId)
    ) throw new TypeError("Invalid activity target");
    const { deviceId, ...rest } = value;
    return deps.readActivity(parent, deviceId, validateActivityReadRequest(rest));
  });
}
export function createGetDeviceControlsHandler(
  deps: ActivityEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    const input = validateDeviceControlsRequest(JSON.parse(raw));
    return deps.readControls(parent, input.deviceId);
  });
}
