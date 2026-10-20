import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/activity-report-v1.json" with { type: "json" };
import {
  ACTIVITY_MAX_PER_REPORT,
  type ActivityEventV1,
  type ActivityReportV1,
  parseActivityReport,
  validateActivityCheckpointReply,
  validateActivityReadReply,
  validateActivityReadRequest,
  validateActivityReport,
  validateActivityWriteReply,
  validateClearActivity,
} from "../src/v1/activity.ts";

const now = Date.parse("2026-10-20T18:00:30Z");
const report = (): ActivityReportV1 => structuredClone(fixture) as ActivityReportV1;
const rejected = (v: unknown) => assert.throws(() => validateActivityReport(v, now), TypeError);
const withEvent = (f: (e: ActivityEventV1) => void, index = 0) => {
  const r = report();
  f(r.events[index]);
  return r;
};

Deno.test("shared fixture is valid and parses from its bytes", () => {
  assert.deepEqual(validateActivityReport(report(), now), fixture);
  assert.deepEqual(parseActivityReport(JSON.stringify(fixture), now), fixture);
});

Deno.test("no field can carry content: extra keys are rejected", () => {
  for (const key of ["title", "text", "url", "windowTitle", "content", "contact"]) {
    rejected(withEvent((e) => Object.assign(e, { [key]: "x" })));
  }
  rejected({ ...report(), notes: "x" });
  rejected({ ...report(), status: { ...report().status, currentApp: "com.example.app" } });
});

Deno.test("app events need package and label; other events have neither", () => {
  rejected(withEvent((e) => (e.packageName = null)));
  rejected(withEvent((e) => (e.appLabel = "")));
  rejected(withEvent((e) => (e.packageName = "not a package")));
  rejected(withEvent((e) => (e.packageName = "com.example.app"), 3));
});

Deno.test("sessions are 1 minute to 24 hours; only sessions have endAt", () => {
  rejected(withEvent((e) => (e.endAt = "2026-10-20T16:00:59Z")));
  rejected(withEvent((e) => (e.endAt = null)));
  rejected(withEvent((e) => (e.endAt = "2026-10-20T16:26:00Z"), 1));
  assert.ok(validateActivityReport(withEvent((e) => (e.endAt = "2026-10-20T16:01:00Z")), now));
});

Deno.test("only blocked_open carries a known reason", () => {
  rejected(withEvent((e) => (e.reason = "parent_said_so" as "paused"), 2));
  rejected(withEvent((e) => (e.reason = null), 2));
  rejected(withEvent((e) => (e.reason = "blocked_app"), 1));
});

Deno.test("events are oldest first, unique, inside the window and not in the future", () => {
  const r = report();
  r.events.reverse();
  rejected(r);
  rejected(withEvent((e) => (e.id = report().events[1].id)));
  rejected(withEvent((e) => (e.at = "2026-10-20T18:00:01Z"), 3));
  const old = report();
  old.events = [{ ...old.events[3], at: "2026-09-20T17:00:00Z" }];
  rejected(old);
  rejected({ ...report(), observedAt: "2026-10-20T18:06:00Z" });
  const many = report();
  many.events = Array.from({ length: ACTIVITY_MAX_PER_REPORT + 1 }, (_, i) => ({
    ...many.events[3],
    id: `00000000-0000-4000-8000-${String(i + 10).padStart(12, "0")}`,
  }));
  rejected(many);
});

Deno.test("clear, checkpoint, write reply and read request", () => {
  assert.ok(validateClearActivity({ version: 1, epochId: fixture.epochId, sequence: 4 }));
  assert.throws(() => validateClearActivity({ version: 1, epochId: fixture.epochId, sequence: 0 }));
  assert.ok(validateActivityCheckpointReply({ sequence: 0, epochId: null }));
  assert.throws(() => validateActivityCheckpointReply({ sequence: 0, epochId: fixture.epochId }));
  assert.ok(validateActivityWriteReply({ confirmed: true, sequence: 3, receivedAt: "2026-10-20T18:00:01Z" }));
  assert.ok(validateActivityReadRequest({ version: 1, from: "2026-10-20T05:00:00Z", to: "2026-10-21T05:00:00Z" }));
  assert.throws(() => validateActivityReadRequest({ version: 1, from: "2026-10-21T05:00:00Z", to: "2026-10-20T05:00:00Z" }));
  assert.throws(() => validateActivityReadRequest({ version: 1, from: "2026-10-18T05:00:00Z", to: "2026-10-21T05:00:00Z" }));
});

Deno.test("read reply: available is complete; none and expired are empty", () => {
  const available = {
    state: "available",
    controlsOn: true,
    policyVersion: 4,
    observedAt: "2026-10-20T18:00:00Z",
    receivedAt: "2026-10-20T18:00:01Z",
    events: report().events,
    truncated: false,
  };
  assert.ok(validateActivityReadReply(available));
  assert.throws(() => validateActivityReadReply({ ...available, controlsOn: null }));
  const none = { state: "none", controlsOn: null, policyVersion: null, observedAt: null, receivedAt: null, events: [], truncated: false };
  assert.ok(validateActivityReadReply(none));
  assert.throws(() => validateActivityReadReply({ ...none, events: report().events }));
});
