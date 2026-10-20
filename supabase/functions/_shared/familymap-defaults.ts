import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import {
  readFamilyMap,
  readSettings,
  reportParentLocation,
  stopParentLocation,
  writeSettings,
} from "./familymap-persistence.ts";
import type { FamilyMapEndpointDependencies } from "./familymap.ts";
export const defaultFamilyMapDependencies: FamilyMapEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  readFamilyMap,
  reportParentLocation,
  stopParentLocation,
  readSettings,
  writeSettings,
};
