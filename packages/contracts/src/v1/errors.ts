export const HarborErrorCodes = [
  "AUTH_REQUIRED",
  "MFA_REQUIRED",
  "FORBIDDEN",
  "VALIDATION_FAILED",
  "DEVICE_REVOKED",
  "DEVICE_OFFLINE",
  "STALE_VERSION",
  "REPLAY_REJECTED",
  "IDEMPOTENCY_CONFLICT",
] as const;

export type HarborErrorCode = (typeof HarborErrorCodes)[number];
