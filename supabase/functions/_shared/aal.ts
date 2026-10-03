import type { ParentContext } from "./auth.ts";
import { HarborAuthError } from "./errors.ts";

export function requireRecentAal2(
  ctx: ParentContext,
  nowEpochSeconds: number,
  maxAgeSeconds = 900,
): void {
  if (ctx.aal !== "aal2") {
    throw new HarborAuthError("MFA_REQUIRED", 403, "Recent MFA verification is required");
  }

  const totpTimestamps = ctx.amr
    .filter((entry) => entry.method === "totp" && Number.isFinite(entry.timestamp))
    .map((entry) => entry.timestamp);

  if (totpTimestamps.length === 0) {
    throw new HarborAuthError("MFA_REQUIRED", 403, "Recent MFA verification is required");
  }

  const mostRecentTotp = Math.max(...totpTimestamps);
  const ageSeconds = nowEpochSeconds - mostRecentTotp;

  if (ageSeconds < 0 || ageSeconds > maxAgeSeconds) {
    throw new HarborAuthError("MFA_REQUIRED", 403, "Recent MFA verification is required");
  }
}
