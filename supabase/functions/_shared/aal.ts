import type { ParentContext } from "./auth.ts";

export function requireRecentAal2(
  _ctx: ParentContext,
  _nowEpochSeconds: number,
  _maxAgeSeconds = 900,
): void {
  throw new Error("NOT_IMPLEMENTED");
}
