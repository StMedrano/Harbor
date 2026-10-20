import {
  LOCATION_MAX_BYTES,
  parseLocationReport,
  validateClearLocation,
  validateLocationCheckpointRequest,
} from "../../../packages/contracts/src/v1/location.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  ClearLocationV1,
  LocationCheckpointReplyV1,
  LocationReadReplyV1,
  LocationReportV1,
  LocationWriteReplyV1,
} from "../../../packages/contracts/src/v1/location.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type LocationEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  writeLocation(
    ctx: DeviceProofContext,
    report: LocationReportV1,
    hash: string,
  ): Promise<LocationWriteReplyV1>;
  clearLocation(
    ctx: DeviceProofContext,
    clear: ClearLocationV1,
    hash: string,
  ): Promise<LocationWriteReplyV1>;
  readLocation(
    parent: ParentContext,
    deviceId: string,
  ): Promise<LocationReadReplyV1>;
  readLocationCheckpoint(
    ctx: DeviceProofContext,
  ): Promise<LocationCheckpointReplyV1>;
};
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, LOCATION_MAX_BYTES, "Location report is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Location request input is invalid");
export function createReportLocationHandler(
  deps: LocationEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "report-device-location");
    const report = parseLocationReport(raw, deps.now());
    return deps.writeLocation(ctx, report, await deps.sha256(raw));
  });
}
export function createClearLocationHandler(deps: LocationEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "clear-device-location");
    const clear = validateClearLocation(JSON.parse(raw));
    return deps.clearLocation(ctx, clear, await deps.sha256(raw));
  });
}
export function createLocationCheckpointHandler(
  deps: LocationEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(
      request,
      "get-device-location-checkpoint",
    );
    validateLocationCheckpointRequest(JSON.parse(raw));
    return deps.readLocationCheckpoint(ctx);
  });
}
export function createGetLocationHandler(deps: LocationEndpointDependencies) {
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
    ) throw new TypeError("Invalid location target");
    return deps.readLocation(parent, value.deviceId);
  });
}
