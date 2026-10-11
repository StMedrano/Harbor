import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/location-report-v1.json" with {
  type: "json",
};
import {
  type LocationReportV1,
  parseLocationReport,
  validateClearLocation,
  validateLocationCheckpointReply,
  validateLocationCheckpointRequest,
  validateLocationReport,
} from "../src/v1/location.ts";
const now = Date.parse("2026-10-10T18:00:00Z");
const report = (): LocationReportV1 =>
  structuredClone(fixture) as LocationReportV1;
const rejected = (v: unknown) =>
  assert.throws(() => validateLocationReport(v, now));
Deno.test("crossLanguageRoundTrip", () =>
  assert.deepEqual(validateLocationReport(report(), now), fixture));
Deno.test("rejectsAuthorityFieldsAndUnknownKeys", () => {
  rejected({ ...report(), familyId: "foreign" });
  rejected({ ...report(), childId: "x" });
  const r = report();
  (r.fixes[0] as Record<string, unknown>).address = "1 Main St";
  rejected(r);
  const noStatus = report() as Record<string, unknown>;
  delete noStatus.status;
  rejected(noStatus);
});
Deno.test("boundsCoordinatesAndAccuracy", () => {
  for (
    const [key, bad] of [
      ["latitude", 90.000001],
      ["latitude", -91],
      ["longitude", 180.5],
      ["longitude", -180.000001],
      ["latitude", Number.NaN],
      ["accuracyM", -1],
      ["accuracyM", 1.5],
      ["accuracyM", 100001],
    ] as const
  ) {
    const r = report();
    (r.fixes[0] as Record<string, unknown>)[key] = bad;
    rejected(r);
  }
  const r = report();
  r.fixes[0].latitude = 41.8781141;
  rejected(r);
  const edge = report();
  edge.fixes[0].latitude = 90;
  edge.fixes[0].longitude = -180;
  edge.fixes[0].accuracyM = 0;
  assert.equal(validateLocationReport(edge, now).fixes[0].latitude, 90);
});
Deno.test("boundsTimeAndOrdering", () => {
  rejected({ ...report(), observedAt: "2026-10-10T18:05:00.001Z" });
  const future = report();
  future.fixes[1].at = "2026-10-10T18:00:01Z";
  rejected(future);
  const old = report();
  old.fixes[0].at = "2026-09-10T17:59:59Z";
  rejected(old);
  const dup = report();
  dup.fixes[1].at = dup.fixes[0].at;
  rejected(dup);
  const unordered = report();
  unordered.fixes.reverse();
  rejected(unordered);
  rejected({ ...report(), observedAt: "2026-10-10 18:00:00" });
});
Deno.test("statesAndEmptyReports", () => {
  for (
    const status of [
      "sharing",
      "permission_lost",
      "background_missing",
      "services_off",
    ]
  ) {
    assert.equal(
      validateLocationReport({ ...report(), fixes: [], status }, now).status,
      status,
    );
  }
  rejected({ ...report(), status: "safe" });
  rejected({ ...report(), status: "denied" });
  rejected({ ...report(), sequence: 0 });
  rejected({ ...report(), epochId: "not-a-uuid" });
  rejected({ ...report(), version: 2 });
});
Deno.test("limitsBatchAndBytes", () => {
  const many = report();
  many.fixes = Array.from({ length: 100 }, (_, i) => ({
    at: new Date(now - (100 - i) * 60000).toISOString().replace(".000Z", "Z"),
    latitude: 41.878114,
    longitude: -87.629798,
    accuracyM: 10,
    provider: "gps" as const,
  }));
  assert.equal(validateLocationReport(many, now).fixes.length, 100);
  many.fixes.push({ ...many.fixes[99], at: "2026-10-10T18:00:00Z" });
  rejected(many);
  const raw = JSON.stringify(report());
  const exact = raw + " ".repeat(262144 - new TextEncoder().encode(raw).length);
  assert.deepEqual(parseLocationReport(exact, now), fixture);
  assert.throws(() => parseLocationReport(exact + " ", now));
  assert.throws(() => parseLocationReport("{bad json", now));
});
Deno.test("providerIsAnEnum", () => {
  const r = report();
  (r.fixes[0] as Record<string, unknown>).provider = "magic";
  rejected(r);
});
Deno.test("clearAndCheckpointContracts", () => {
  const clear = { version: 1, epochId: fixture.epochId, sequence: 8 };
  assert.deepEqual(validateClearLocation(clear), clear);
  assert.throws(() => validateClearLocation({ ...clear, extra: 1 }));
  assert.throws(() => validateClearLocation({ ...clear, sequence: 0 }));
  assert.deepEqual(validateLocationCheckpointRequest({ version: 1 }), {
    version: 1,
  });
  assert.throws(() => validateLocationCheckpointRequest({ version: 1, x: 1 }));
  assert.deepEqual(
    validateLocationCheckpointReply({ sequence: 0, epochId: null }),
    { sequence: 0, epochId: null },
  );
  assert.throws(() =>
    validateLocationCheckpointReply({ sequence: 0, epochId: fixture.epochId })
  );
  assert.throws(() =>
    validateLocationCheckpointReply({ sequence: 3, epochId: null })
  );
});
