import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import { sha256Hex } from "./crypto.ts";
import {
  clearActivity,
  readActivity,
  readActivityCheckpoint,
  readControls,
  writeActivity,
} from "./activity-persistence.ts";
import type { ActivityEndpointDependencies } from "./activity.ts";
export const defaultActivityDependencies: ActivityEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  sha256: sha256Hex,
  writeActivity,
  clearActivity,
  readActivity,
  readActivityCheckpoint,
  readControls,
};
