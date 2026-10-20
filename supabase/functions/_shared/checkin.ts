import {
  CHECKIN_MAX_BYTES,
  parseCheckInRead,
  parseCheckInReply,
  parseCheckInRequest,
  validateCheckInStateRequest,
} from "../../../packages/contracts/src/v1/checkin.ts";
import { HarborAuthError } from "./errors.ts";
import { boundedRequest as bounded, postHandler } from "./endpoint.ts";
import type {
  CheckInReadReplyV1,
  CheckInReplyInputV1,
  CheckInReplyReceiptV1,
  CheckInRequestReplyV1,
  CheckInStateReplyV1,
} from "../../../packages/contracts/src/v1/checkin.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type CheckInEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  requestCheckIn(
    parent: ParentContext,
    deviceId: string,
  ): Promise<CheckInRequestReplyV1>;
  readCheckIns(
    parent: ParentContext,
    deviceId: string,
  ): Promise<CheckInReadReplyV1>;
  readChildState(ctx: DeviceProofContext): Promise<CheckInStateReplyV1>;
  replyCheckIn(
    ctx: DeviceProofContext,
    reply: CheckInReplyInputV1,
    hash: string,
  ): Promise<CheckInReplyReceiptV1>;
};
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const boundedRequest = (req: Request) =>
  bounded(req, CHECKIN_MAX_BYTES, "Check-in request is too large");
const handler = (action: (req: Request) => Promise<unknown>) =>
  postHandler(action, "Check-in request input is invalid");
async function currentParent(deps: CheckInEndpointDependencies, request: Request) {
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
export function createRequestCheckInHandler(deps: CheckInEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    const input = parseCheckInRequest(raw);
    return deps.requestCheckIn(parent, input.deviceId);
  });
}
export function createReadCheckInsHandler(deps: CheckInEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await currentParent(deps, request);
    const input = parseCheckInRead(raw);
    return deps.readCheckIns(parent, input.deviceId);
  });
}
export function createCheckInStateHandler(deps: CheckInEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "get-checkin-state");
    validateCheckInStateRequest(JSON.parse(raw));
    return deps.readChildState(ctx);
  });
}
export function createReplyCheckInHandler(deps: CheckInEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "reply-checkin");
    const reply = parseCheckInReply(raw, deps.now());
    return deps.replyCheckIn(ctx, reply, await deps.sha256(raw));
  });
}
