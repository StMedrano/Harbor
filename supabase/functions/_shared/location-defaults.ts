import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import { sha256Hex } from "./crypto.ts";
import {
  clearLocation,
  readLocation,
  readLocationCheckpoint,
  writeLocation,
} from "./location-persistence.ts";
import type { LocationEndpointDependencies } from "./location.ts";
export const defaultLocationDependencies: LocationEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  sha256: sha256Hex,
  writeLocation,
  clearLocation,
  readLocation,
  readLocationCheckpoint,
};
