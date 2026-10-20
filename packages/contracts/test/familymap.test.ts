import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/family-map-reply-v1.json" with {
  type: "json",
};
import {
  parseFamilyMapSettingsRequest,
  parseFamilyMapSettingsUpdate,
  parseParentLocationReport,
  parseParentLocationStop,
  validateFamilyMapReply,
  validateFamilyMapRequest,
  validateFamilyMapSettingsReply,
  validateParentLocationReceipt,
} from "../src/v1/familymap.ts";
const now = Date.parse("2026-10-20T16:00:00Z");
const fam = "3b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74";
const report = () => ({
  version: 1,
  familyId: fam,
  label: "Alex",
  fix: { at: "2026-10-20T15:59:00Z", latitude: 41.5, longitude: -87.6, accuracyM: 20 },
});
const bad = (fn: () => unknown) => assert.throws(fn);

Deno.test("replyRoundTripsTheSharedFixture", () => {
  assert.deepEqual(validateFamilyMapReply(fixture), fixture);
});
Deno.test("replyRejectsParentsWithoutAPlaceAndExtraKeys", () => {
  const r = structuredClone(fixture);
  r.people[2].fix = null;
  bad(() => validateFamilyMapReply(r));
  bad(() => validateFamilyMapReply({ ...fixture, extra: 1 }));
  const withEmail = structuredClone(fixture) as Record<string, unknown>;
  (withEmail.people as Record<string, unknown>[])[0].email = "x@example.invalid";
  bad(() => validateFamilyMapReply(withEmail));
});
Deno.test("replyWhenDisabledHasNoPeopleAndAtMostOneSelfWhoIsAChild", () => {
  assert.deepEqual(validateFamilyMapReply({ enabled: false, people: [] }), { enabled: false, people: [] });
  bad(() => validateFamilyMapReply({ ...fixture, enabled: false }));
  const twoSelves = structuredClone(fixture);
  twoSelves.people[1].self = true;
  bad(() => validateFamilyMapReply(twoSelves));
  const parentSelf = structuredClone(fixture);
  parentSelf.people[0].self = false;
  parentSelf.people[2].self = true;
  bad(() => validateFamilyMapReply(parentSelf));
});
Deno.test("replyRejectsDuplicateIdsAndBadCoordinates", () => {
  const dup = structuredClone(fixture);
  dup.people[1].id = dup.people[0].id;
  bad(() => validateFamilyMapReply(dup));
  const far = structuredClone(fixture);
  far.people[0].fix!.latitude = 91;
  bad(() => validateFamilyMapReply(far));
  const precise = structuredClone(fixture);
  precise.people[0].fix!.longitude = -87.1234567;
  bad(() => validateFamilyMapReply(precise));
});
Deno.test("parentReportNeedsAFreshPlaceAndAPlainLabel", () => {
  assert.deepEqual(parseParentLocationReport(JSON.stringify(report()), now), report());
  const old = report();
  old.fix.at = "2026-10-20T15:00:00Z";
  bad(() => parseParentLocationReport(JSON.stringify(old), now));
  const future = report();
  future.fix.at = "2026-10-20T16:30:00Z";
  bad(() => parseParentLocationReport(JSON.stringify(future), now));
  for (const label of ["", " Alex", "Alex ", "A".repeat(41), "Al\u0000ex"]) {
    bad(() => parseParentLocationReport(JSON.stringify({ ...report(), label }), now));
  }
  bad(() => parseParentLocationReport(JSON.stringify({ ...report(), extra: true }), now));
  bad(() => parseParentLocationReport("x".repeat(5000), now));
});
Deno.test("stopSettingsAndRequestAreStrict", () => {
  assert.deepEqual(parseParentLocationStop(JSON.stringify({ version: 1, familyId: fam })), { version: 1, familyId: fam });
  assert.deepEqual(parseFamilyMapSettingsRequest(JSON.stringify({ version: 1, familyId: fam })), { version: 1, familyId: fam });
  bad(() => parseParentLocationStop(JSON.stringify({ version: 1, familyId: "nope" })));
  assert.deepEqual(parseFamilyMapSettingsUpdate(JSON.stringify({ version: 1, familyId: fam, childrenCanView: false })), { version: 1, familyId: fam, childrenCanView: false });
  bad(() => parseFamilyMapSettingsUpdate(JSON.stringify({ version: 1, familyId: fam, childrenCanView: "no" })));
  assert.deepEqual(validateFamilyMapRequest({ version: 1 }), { version: 1 });
  bad(() => validateFamilyMapRequest({ version: 2 }));
});
Deno.test("receiptAndSettingsReplyAreValidated", () => {
  validateParentLocationReceipt({ confirmed: true, receivedAt: "2026-10-20T16:00:00Z" });
  bad(() => validateParentLocationReceipt({ confirmed: false, receivedAt: "2026-10-20T16:00:00Z" }));
  validateFamilyMapSettingsReply({ childrenCanView: true, mySharing: null });
  validateFamilyMapSettingsReply({ childrenCanView: false, mySharing: { label: "Alex", lastAt: "2026-10-20T15:00:00Z" } });
  bad(() => validateFamilyMapSettingsReply({ childrenCanView: true, mySharing: { label: "Alex" } }));
});
