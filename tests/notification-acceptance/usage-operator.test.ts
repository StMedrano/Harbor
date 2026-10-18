import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import fixture from "../../apps/parent-android/app/src/test/resources/usage-report-v1.json" with {
  type: "json",
};
import {
  evaluateObservation,
  publicStageSummary,
  redactInventory,
  validateUsageManifest,
} from "../hosted/usage-reporting-acceptance.ts";
import { projectRef } from "../hosted/notification-acceptance.ts";
import type { UsageReportV1 } from "../../packages/contracts/src/v1/usage.ts";
const id = (n: number) =>
  `${String(n).padStart(8, "0")}-1111-4111-8111-111111111111`;
const report = structuredClone(fixture) as UsageReportV1;
const window = {
  localDate: "2026-10-08",
  intervalsEndedAt: Date.parse("2026-10-08T11:59:00Z"),
  notBefore: Date.parse("2026-10-08T11:59:30Z"),
};
const received = "2026-10-08T12:00:10.000Z";
Deno.test("usage manifest requires the exact device", () => {
  const base = {
    runId: id(1), projectRef, parentUserId: id(2), familyId: id(3),
    childId: id(4), expectedDesiredStateVersion: 0, subscriptionIds: [],
  };
  assertThrows(() => validateUsageManifest(base));
  assertEquals(validateUsageManifest({ ...base, deviceId: id(5) }).deviceId, id(5));
});
Deno.test("observed times are compared with the recorded tolerance, never widened", () => {
  const [exact, off] = evaluateObservation(report, received, window, [
    { packageName: "example.test", expectedMs: 61_000, toleranceMs: 2_000 },
    { packageName: "example.test", expectedMs: 90_000, toleranceMs: 2_000 },
  ]);
  assertEquals([exact.observedMs, exact.deltaMs, exact.within], [60_000, -1_000, true]);
  assertEquals([off.deltaMs, off.within], [-30_000, false]);
});
Deno.test("an app absent from a measured day is recorded as zero, an unavailable day as unknown", () => {
  const [none] = evaluateObservation(report, received, window, [
    { packageName: "example.absent", expectedMs: 0, toleranceMs: 0 },
  ]);
  assertEquals([none.observedMs, none.within], [0, true]);
  const unavailable = structuredClone(report);
  unavailable.days[0] = { ...unavailable.days[0], quality: "unavailable", totalMs: null, coverageStart: null, apps: [] };
  const [unknown] = evaluateObservation(unavailable, received, window, [
    { packageName: "example.test", expectedMs: 60_000, toleranceMs: 1_000 },
  ]);
  assertEquals([unknown.observedMs, unknown.deltaMs, unknown.within], [null, null, null]);
});
Deno.test("reports that predate the controlled intervals or their confirmation are not evidence", () => {
  const controlled = [{ packageName: "example.test", expectedMs: 60_000, toleranceMs: 1_000 }];
  assertThrows(() => evaluateObservation(report, null, window, controlled));
  assertThrows(() => evaluateObservation(report, "2026-10-08T11:00:00.000Z", window, controlled));
  assertThrows(() => evaluateObservation(report, received, { ...window, intervalsEndedAt: Date.parse("2026-10-08T12:30:00Z") }, controlled));
  assertThrows(() => evaluateObservation(report, received, { ...window, localDate: "2026-10-01" }, controlled));
  assertThrows(() => evaluateObservation(report, received, window, []));
});
Deno.test("public output has fixed stages and journals lose inventory", () => {
  assertEquals(
    publicStageSummary([{ name: "migrations", outcome: "passed" }, { name: "opt-out", outcome: "blocked" }]),
    "migrations: passed\nopt-out: blocked",
  );
  assertThrows(() => publicStageSummary([{ name: "migrations", outcome: "passed" }, { name: "migrations", outcome: "failed" }]));
  assertThrows(() => publicStageSummary([{ name: "my-phone" as never, outcome: "passed" }]));
  const redacted = redactInventory("opened example.test (Test app) twice", report);
  assertEquals(redacted, "opened [package] ([label]) twice");
});
