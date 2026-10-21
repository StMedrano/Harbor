import {
  ACCESS_MAX_BYTES,
  type AccessDecisionReplyV1,
  type AccessDecisionV1,
  type AccessRequestReplyV1,
  type AccessRequestsReplyV1,
  type AccessRequestV1,
  parseAccessRequest,
  validateAccessDecision,
  validateAccessRequestsRead,
} from "../../../packages/contracts/src/v1/access-requests.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";

export type AccessEndpointDependencies = {
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  requestAccess(ctx: DeviceProofContext, request: AccessRequestV1): Promise<AccessRequestReplyV1>;
  readRequests(parent: ParentContext, deviceId: string): Promise<AccessRequestsReplyV1>;
  decide(parent: ParentContext, decision: AccessDecisionV1): Promise<AccessDecisionReplyV1>;
};
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) => bounded(req, ACCESS_MAX_BYTES, "Access request is too large");
const handler = (action: (req: Request) => Promise<unknown>) => postHandler(action, "Access request input is invalid");
async function currentParent(deps: AccessEndpointDependencies, request: Request) {
  const parent = await deps.requireParent(request);
  if (!parent.sessionId || !uuid.test(parent.sessionId)) {
    throw new HarborAuthError("AUTH_REQUIRED", 401, "Current parent session is required");
  }
  return parent;
}
/** Child phone (signed): ask the parents for more time or access to one app. */
export function createRequestAccessHandler(deps: AccessEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "request-device-access");
    return deps.requestAccess(ctx, parseAccessRequest(raw));
  });
}
/** Parent: the last day's requests for one phone. */
export function createGetAccessRequestsHandler(deps: AccessEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    return deps.readRequests(parent, validateAccessRequestsRead(JSON.parse(raw)).deviceId);
  });
}
/** Parent: answer one request. */
export function createDecideAccessHandler(deps: AccessEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    return deps.decide(parent, validateAccessDecision(JSON.parse(raw)));
  });
}
