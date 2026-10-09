// Opt-in development acceptance helpers for Android usage reporting. Imports perform no hosted calls.
// Controlled intervals are produced by a test app the operator drives; nothing here measures,
// estimates or fabricates usage. Structural validation is not cleanup authority: use trusted
// exact-run SQL/Auth discovery and the existing guarded cleanup phases before deleting anything.
import {
  type FixtureManifest,
  validateManifest,
} from "./notification-acceptance.ts";
import type { UsageReportV1 } from "../../packages/contracts/src/v1/usage.ts";

export type UsageFixtureManifest = FixtureManifest & { deviceId: string };
export function validateUsageManifest(value: unknown): UsageFixtureManifest {
  const manifest = validateManifest(value);
  if (!manifest.deviceId) throw Error("Usage acceptance needs the exact device");
  return { ...manifest, deviceId: manifest.deviceId };
}

/** One foreground interval the operator deliberately produced with a disposable test app. */
export type ControlledInterval = {
  packageName: string;
  expectedMs: number;
  /** Actual tolerance for this run; record it, do not widen it to make a result pass. */
  toleranceMs: number;
};
export type ObservationOutcome = {
  packageName: string;
  observedMs: number | null;
  deltaMs: number | null;
  /** null means the day was not measured, so nothing can be said about this app. */
  within: boolean | null;
};
export type ObservationWindow = {
  localDate: string;
  /** Wall-clock end of the last controlled interval, ms since epoch. */
  intervalsEndedAt: number;
  /** Server receipt must be at or after this time, ms since epoch. */
  notBefore: number;
};

export function evaluateObservation(
  report: UsageReportV1,
  receivedAt: string | null,
  window: ObservationWindow,
  controlled: ControlledInterval[],
): ObservationOutcome[] {
  if (!controlled.length) throw Error("No controlled intervals to compare");
  for (const c of controlled) {
    if (
      !Number.isSafeInteger(c.expectedMs) || c.expectedMs < 0 ||
      !Number.isSafeInteger(c.toleranceMs) || c.toleranceMs < 0
    ) throw Error("Invalid controlled interval");
  }
  const received = receivedAt === null ? NaN : Date.parse(receivedAt);
  if (!Number.isFinite(received) || received < window.notBefore) {
    throw Error("Report was not confirmed after the controlled intervals");
  }
  if (Date.parse(report.observedAt) < window.intervalsEndedAt) {
    throw Error("Report was measured before the controlled intervals ended");
  }
  const day = report.days.find((d) => d.localDate === window.localDate);
  if (!day) throw Error("Report does not contain the controlled day");
  return controlled.map((c) => {
    if (day.quality === "unavailable" || day.totalMs === null) {
      return {
        packageName: c.packageName,
        observedMs: null,
        deltaMs: null,
        within: null,
      };
    }
    const observed = day.apps.find((a) => a.packageName === c.packageName)
      ?.foregroundMs ?? 0;
    const delta = observed - c.expectedMs;
    return {
      packageName: c.packageName,
      observedMs: observed,
      deltaMs: delta,
      within: Math.abs(delta) <= c.toleranceMs,
    };
  });
}

export const usageStageNames = [
  "migrations",
  "functions",
  "retention-job",
  "grants",
  "child-report",
  "parent-native-read",
  "parent-web-read",
  "permission-lost",
  "offline-reconnect",
  "opt-out",
  "revoked-denied",
  "cleanup",
] as const;
export type UsageStageName = (typeof usageStageNames)[number];
export type UsageStage = {
  name: UsageStageName;
  outcome: "passed" | "failed" | "blocked" | "not-run";
};

/** Public progress line: fixed stage names and outcomes only, never identifiers or inventory. */
export function publicStageSummary(stages: UsageStage[]): string {
  const known = new Set<string>(usageStageNames);
  const seen = new Set<string>();
  return stages.map((s) => {
    if (
      !known.has(s.name) || seen.has(s.name) ||
      !["passed", "failed", "blocked", "not-run"].includes(s.outcome)
    ) throw Error("Invalid usage acceptance stage");
    seen.add(s.name);
    return `${s.name}: ${s.outcome}`;
  }).join("\n");
}

/** Redacts every package name seen in a private journal before text is shared. */
export function redactInventory(text: string, report: UsageReportV1): string {
  const names = new Set([
    ...report.inventory.map((i) => i.packageName),
    ...report.days.flatMap((d) => d.apps.map((a) => a.packageName)),
  ]);
  let out = text;
  for (const name of [...names].sort((a, b) => b.length - a.length)) {
    out = out.split(name).join("[package]");
  }
  for (const item of report.inventory) {
    if (item.label.length > 2) out = out.split(item.label).join("[label]");
  }
  return out;
}
