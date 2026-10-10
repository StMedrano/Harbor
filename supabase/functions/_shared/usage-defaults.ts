import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import { sha256Hex } from "./crypto.ts";
import {
  clearUsage,
  readUsage,
  readUsageCheckpoint,
  writeUsage,
} from "./usage-persistence.ts";
import type { UsageEndpointDependencies } from "./usage.ts";
export const defaultUsageDependencies: UsageEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  sha256: sha256Hex,
  writeUsage,
  clearUsage,
  readUsage,
  readUsageCheckpoint,
};
