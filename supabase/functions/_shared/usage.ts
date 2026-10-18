import {
  parseUsageReport,
  validateClearUsage,
  validateUsageCheckpointRequest,
} from "../../../packages/contracts/src/v1/usage.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  ClearUsageV1,
  UsageCheckpointReplyV1,
  UsageReadReplyV1,
  UsageReportV1,
  UsageWriteReplyV1,
} from "../../../packages/contracts/src/v1/usage.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type UsageEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  writeUsage(
    ctx: DeviceProofContext,
    report: UsageReportV1,
    hash: string,
  ): Promise<UsageWriteReplyV1>;
  clearUsage(
    ctx: DeviceProofContext,
    clear: ClearUsageV1,
    hash: string,
  ): Promise<UsageWriteReplyV1>;
  readUsage(parent: ParentContext, deviceId: string): Promise<UsageReadReplyV1>;
  readUsageCheckpoint(ctx: DeviceProofContext): Promise<UsageCheckpointReplyV1>;
};
const MAX_BYTES = 1_048_576;
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, MAX_BYTES, "Usage report is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Usage request input is invalid");
export function createReportUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "report-device-usage");
    const report = parseUsageReport(raw, deps.now());
    return deps.writeUsage(ctx, report, await deps.sha256(raw));
  });
}
export function createClearUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "clear-device-usage");
    const clear = validateClearUsage(JSON.parse(raw));
    return deps.clearUsage(ctx, clear, await deps.sha256(raw));
  });
}
export function createUsageCheckpointHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "get-device-usage-checkpoint");
    validateUsageCheckpointRequest(JSON.parse(raw));
    return deps.readUsageCheckpoint(ctx);
  });
}
export function createGetUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await deps.requireParent(request);
    if (!parent.sessionId || !uuid.test(parent.sessionId)) {
      throw new HarborAuthError(
        "AUTH_REQUIRED",
        401,
        "Current parent session is required",
      );
    }
    const value = JSON.parse(raw);
    if (
      !value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length !== 1 || typeof value.deviceId !== "string" ||
      !uuid.test(value.deviceId)
    ) throw new TypeError("Invalid usage target");
    return deps.readUsage(parent, value.deviceId);
  });
}
