import { requireParent } from "./auth.ts";
import { defaultDeviceProofDependencies, requireDeviceProof } from "./device-proof.ts";
import { decide, readRequests, requestAccess } from "./access-requests-persistence.ts";
import type { AccessEndpointDependencies } from "./access-requests.ts";
export const defaultAccessDependencies: AccessEndpointDependencies = {
  requireProof: (request, operation) => requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  requestAccess,
  readRequests,
  decide,
};
