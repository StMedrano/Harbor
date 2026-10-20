import {
  FAMILY_MAP_MAX_BYTES,
  parseFamilyMapSettingsRequest,
  parseFamilyMapSettingsUpdate,
  parseParentLocationReport,
  parseParentLocationStop,
  validateFamilyMapRequest,
} from "../../../packages/contracts/src/v1/familymap.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  FamilyMapReplyV1,
  FamilyMapSettingsReplyV1,
  ParentLocationReceiptV1,
  ParentLocationReportV1,
} from "../../../packages/contracts/src/v1/familymap.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type FamilyMapEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  readFamilyMap(ctx: DeviceProofContext): Promise<FamilyMapReplyV1>;
  reportParentLocation(
    parent: ParentContext,
    report: ParentLocationReportV1,
  ): Promise<ParentLocationReceiptV1>;
  stopParentLocation(
    parent: ParentContext,
    familyId: string,
  ): Promise<ParentLocationReceiptV1>;
  readSettings(
    parent: ParentContext,
    familyId: string,
  ): Promise<FamilyMapSettingsReplyV1>;
  writeSettings(
    parent: ParentContext,
    familyId: string,
    childrenCanView: boolean,
  ): Promise<FamilyMapSettingsReplyV1>;
};
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, FAMILY_MAP_MAX_BYTES, "Family map request is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Family map request input is invalid");
async function currentParent(
  deps: FamilyMapEndpointDependencies,
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
export function createFamilyMapHandler(deps: FamilyMapEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "get-family-map");
    validateFamilyMapRequest(JSON.parse(raw));
    return deps.readFamilyMap(ctx);
  });
}
export function createReportParentLocationHandler(
  deps: FamilyMapEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    return deps.reportParentLocation(
      parent,
      parseParentLocationReport(raw, deps.now()),
    );
  });
}
export function createStopParentLocationHandler(
  deps: FamilyMapEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    return deps.stopParentLocation(parent, parseParentLocationStop(raw).familyId);
  });
}
export function createGetFamilyMapSettingsHandler(
  deps: FamilyMapEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    return deps.readSettings(
      parent,
      parseFamilyMapSettingsRequest(raw).familyId,
    );
  });
}
export function createSetFamilyMapSettingsHandler(
  deps: FamilyMapEndpointDependencies,
) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    const input = parseFamilyMapSettingsUpdate(raw);
    return deps.writeSettings(parent, input.familyId, input.childrenCanView);
  });
}
