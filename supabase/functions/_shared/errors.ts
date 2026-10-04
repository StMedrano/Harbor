import type { HarborErrorCode } from "../../../packages/contracts/src/v1/errors.ts";

export class HarborAuthError extends Error {
  readonly code: HarborErrorCode;
  readonly status: number;

  constructor(code: HarborErrorCode, status: number, message: string) {
    super(message);
    this.name = "HarborAuthError";
    this.code = code;
    this.status = status;
  }
}

export function databaseError(error: unknown): HarborAuthError | null {
  if (error instanceof HarborAuthError) return error;
  if (!error || typeof error !== "object") return null;
  const { code, message } = error as { code?: unknown; message?: unknown };
  if (code === "P0001" && message === "STALE_VERSION") return new HarborAuthError("STALE_VERSION", 409, "Desired-state version is stale");
  if (code === "42501" && message === "DEVICE_REVOKED") return new HarborAuthError("DEVICE_REVOKED", 403, "Device has been revoked");
  if (code === "42501" || (code === "23505" && message === "device identity is already bound")) return new HarborAuthError("FORBIDDEN", 403, "Operation is not authorized");
  if (code === "22023" || code === "22P02" || code === "23505" || (code === "P0001" && message === "VALIDATION_FAILED")) return new HarborAuthError("VALIDATION_FAILED", 400, "Request input is invalid");
  return null;
}
