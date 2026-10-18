import {
  ALERT_MAX_BYTES,
  parseAlertReport,
  validateAlertCheckpointRequest,
  validateAlertReadRequest,
  validateClearAlerts,
} from "../../../packages/contracts/src/v1/alerts.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  AlertCheckpointReplyV1,
  AlertReadReplyV1,
  AlertReadRequestV1,
  AlertReportV1,
  AlertWriteReplyV1,
  ClearAlertsV1,
} from "../../../packages/contracts/src/v1/alerts.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type AlertEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  writeAlerts(
    ctx: DeviceProofContext,
    report: AlertReportV1,
    hash: string,
  ): Promise<AlertWriteReplyV1>;
  clearAlerts(
    ctx: DeviceProofContext,
    clear: ClearAlertsV1,
    hash: string,
  ): Promise<AlertWriteReplyV1>;
  readAlerts(
    parent: ParentContext,
    deviceId: string,
    page: AlertReadRequestV1,
  ): Promise<AlertReadReplyV1>;
  readAlertCheckpoint(
    ctx: DeviceProofContext,
  ): Promise<AlertCheckpointReplyV1>;
};
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, ALERT_MAX_BYTES, "Alert report is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Alert request input is invalid");
export function createReportAlertsHandler(deps: AlertEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "report-message-alert");
    const report = parseAlertReport(raw, deps.now());
    return deps.writeAlerts(ctx, report, await deps.sha256(raw));
  });
}
export function createClearAlertsHandler(deps: AlertEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "clear-message-alerts");
    const clear = validateClearAlerts(JSON.parse(raw));
    return deps.clearAlerts(ctx, clear, await deps.sha256(raw));
  });
}
export function createAlertCheckpointHandler(deps: AlertEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "get-message-alert-checkpoint");
    validateAlertCheckpointRequest(JSON.parse(raw));
    return deps.readAlertCheckpoint(ctx);
  });
}
export function createGetAlertsHandler(deps: AlertEndpointDependencies) {
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
      typeof value.deviceId !== "string" || !uuid.test(value.deviceId)
    ) throw new TypeError("Invalid alert target");
    const { deviceId, ...rest } = value;
    const page = validateAlertReadRequest(rest);
    return deps.readAlerts(parent, deviceId, page);
  });
}
