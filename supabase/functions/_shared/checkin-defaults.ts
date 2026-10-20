import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import { sha256Hex } from "./crypto.ts";
import {
  readChildState,
  readCheckIns,
  replyCheckIn,
  requestCheckIn,
} from "./checkin-persistence.ts";
import type { CheckInEndpointDependencies } from "./checkin.ts";
export const defaultCheckInDependencies: CheckInEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  sha256: sha256Hex,
  requestCheckIn,
  readCheckIns,
  readChildState,
  replyCheckIn,
};
