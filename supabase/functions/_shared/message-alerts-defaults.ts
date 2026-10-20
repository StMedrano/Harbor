import { requireParent } from "./auth.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
} from "./device-proof.ts";
import { sha256Hex } from "./crypto.ts";
import {
  clearAlerts,
  readAlertCheckpoint,
  readAlerts,
  writeAlerts,
} from "./message-alerts-persistence.ts";
import type { AlertEndpointDependencies } from "./message-alerts.ts";
export const defaultAlertDependencies: AlertEndpointDependencies = {
  now: () => Date.now(),
  requireProof: (request, operation) =>
    requireDeviceProof(request, operation, defaultDeviceProofDependencies),
  requireParent,
  sha256: sha256Hex,
  writeAlerts,
  clearAlerts,
  readAlerts,
  readAlertCheckpoint,
};
