import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/usage-report-v1.json" with {
  type: "json",
};
import {
  parseUsageReport,
  type UsageReportV1,
  validateClearUsage,
  validateUsageCheckpointReply,
  validateUsageCheckpointRequest,
  validateUsageReport,
} from "../src/v1/usage.ts";
const now = Date.parse("2026-10-08T12:00:00Z");
const report = (): UsageReportV1 => structuredClone(fixture) as UsageReportV1;
const rejected = (v: unknown) =>
  assert.throws(() => validateUsageReport(v, now));
Deno.test("crossLanguageRoundTrip", () =>
  assert.deepEqual(validateUsageReport(report(), now), fixture));
Deno.test("rejectsAuthorityAndOversize", () => {
  rejected({ ...report(), familyId: "foreign" });
  const raw = JSON.stringify(report());
  const exact = raw +
    " ".repeat(1048576 - new TextEncoder().encode(raw).length);
  assert.deepEqual(parseUsageReport(exact, now), fixture);
  assert.throws(() => parseUsageReport(exact + " ", now));
  assert.throws(() => parseUsageReport("{bad json", now));
});
Deno.test("preservesUnknownDurations", () => {
  const r = report();
  Object.assign(r.days[0], {
    quality: "unavailable",
    totalMs: null,
    coverageStart: null,
    apps: [],
  });
  const v = validateUsageReport(r, now);
  assert.equal(v.days[0].totalMs, null);
  rejected({ ...r, days: [{ ...r.days[0], totalMs: 0 }] });
});
Deno.test("boundsDailyElapsed", () => {
  const r = report();
  r.days[0].totalMs = 43200001;
  rejected(r);
  r.days[0].totalMs = 60000;
  r.days[0].apps[0].foregroundMs = -1;
  rejected(r);
  r.days[0].apps[0].foregroundMs = 1.5;
  rejected(r);
  r.days[0].apps[0].foregroundMs = 60000;
  r.days[0].startAt = "2026-10-08T01:00:00Z";
  rejected(r);
  rejected({ ...report(), observedAt: "2026-10-08T12:05:00.001Z" });
});
Deno.test("rejectsDuplicatePackagesAndDays", () => {
  const r = report();
  r.inventory.push(r.inventory[0]);
  rejected(r);
  r.inventory.pop();
  r.days[0].apps.push(r.days[0].apps[0]);
  rejected(r);
  r.days[0].apps.pop();
  r.days.push(r.days[0]);
  rejected(r);
  rejected({ ...report(), zoneId: "Fake/Zone" });
});
Deno.test("truncationIsExplicit", () => {
  const r = report();
  r.inventoryStatus = "truncated";
  assert.equal(validateUsageReport(r, now).inventoryStatus, "truncated");
  r.inventory = Array.from(
    { length: 501 },
    (_, i) => ({ packageName: `example.app${i}`, label: "App" }),
  );
  rejected(r);
  r.inventory = [];
  r.days[0].apps = Array.from(
    { length: 3501 },
    (_, i) => ({ packageName: `example.app${i}`, foregroundMs: 1 }),
  );
  rejected(r);
  const x = report();
  x.inventoryStatus = "unavailable";
  rejected(x);
});
Deno.test("safeLabelsAndPackages", () => {
  const r = report();
  r.inventory[0].label = "<script>alert(1)</script>";
  assert.equal(
    validateUsageReport(r, now).inventory[0].label,
    r.inventory[0].label,
  );
  r.inventory[0].label = "😀".repeat(200);
  validateUsageReport(r, now);
  r.inventory[0].label += "😀";
  rejected(r);
  r.inventory[0].label = "bad\u0000label";
  rejected(r);
  r.inventory[0].label = "App";
  r.inventory[0].packageName = "not a package";
  rejected(r);
  rejected({ ...report(), sequence: Number.MAX_SAFE_INTEGER + 1 });
});
Deno.test("windowAndQualityStates", () => {
  const r = report();
  r.days[0].localDate = "2026-10-01";
  rejected(r);
  const x = report();
  x.days[0].quality = "partial";
  x.days[0].coverageStart = "2026-10-08T01:00:00Z";
  validateUsageReport(x, now);
  x.days[0].coverageStart = null;
  x.days[0].quality = "observed";
  rejected(x);
  const y = report();
  y.days[0].endAt = "2026-10-09T01:00:00Z";
  rejected(y);
});
Deno.test("clearAndCheckpointAreBindingFreeStrictContracts", () => {
  const clear = { version: 1, epochId: fixture.epochId, sequence: 2 };
  assert.deepEqual(validateClearUsage(clear), clear);
  assert.throws(() => validateClearUsage({ ...clear, deviceId: "authority" }));
  assert.throws(() => validateClearUsage({ ...clear, sequence: 0 }));
  assert.deepEqual(validateUsageCheckpointRequest({ version: 1 }), {
    version: 1,
  });
  assert.throws(() =>
    validateUsageCheckpointRequest({ version: 1, deviceId: "authority" })
  );
  assert.deepEqual(
    validateUsageCheckpointReply({ sequence: 0, epochId: null }),
    { sequence: 0, epochId: null },
  );
  assert.throws(() =>
    validateUsageCheckpointReply({ sequence: 1, epochId: null })
  );
});
Deno.test("rejectsUnicodeControlLabels", () => {
  const r = report();
  r.inventory[0].label = "bad\u0085label";
  rejected(r);
});
