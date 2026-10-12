import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/alert-report-v1.json" with {
  type: "json",
};
import {
  ALERT_CATEGORIES,
  type AlertReadReplyV1,
  type AlertReportV1,
  parseAlertReport,
  validateAlertCheckpointReply,
  validateAlertCheckpointRequest,
  validateAlertReadReply,
  validateAlertReadRequest,
  validateAlertReport,
  validateAlertWriteReply,
  validateClearAlerts,
} from "../src/v1/alerts.ts";
const now = Date.parse("2026-10-10T18:00:00Z");
const report = (): AlertReportV1 =>
  structuredClone(fixture) as AlertReportV1;
const rejected = (v: unknown) =>
  assert.throws(() => validateAlertReport(v, now));
const alertAt = (n: number) => ({
  id: `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`,
  appLabel: "Messages",
  category: "bullying" as const,
  ruleSetVersion: 1,
  at: new Date(Date.parse("2026-10-10T12:00:00Z") + n * 1000).toISOString()
    .replace(".000Z", "Z"),
  savedContact: true,
});

Deno.test("crossLanguageRoundTrip", () =>
  assert.deepEqual(validateAlertReport(report(), now), fixture));

Deno.test("parseEnforcesByteLimit", () => {
  assert.deepEqual(parseAlertReport(JSON.stringify(fixture), now), fixture);
  assert.throws(() => parseAlertReport(" ".repeat(65_537), now));
});

Deno.test("noTextSenderOrExcerptFieldExists", () => {
  for (
    const key of [
      "text",
      "body",
      "message",
      "excerpt",
      "snippet",
      "sender",
      "from",
      "address",
      "phone",
      "contact",
      "contactName",
      "title",
      "preview",
      "conversation",
      "thread",
    ]
  ) {
    const r = report();
    (r.alerts[0] as Record<string, unknown>)[key] = "anything";
    rejected(r);
    rejected({ ...report(), [key]: "anything" });
  }
});

Deno.test("rejectsAuthorityFieldsAndMissingKeys", () => {
  rejected({ ...report(), familyId: "foreign" });
  rejected({ ...report(), childId: "x" });
  for (const key of Object.keys(fixture)) {
    const r = report() as Record<string, unknown>;
    delete r[key];
    rejected(r);
  }
  for (const key of Object.keys(fixture.alerts[0])) {
    const r = report();
    delete (r.alerts[0] as Record<string, unknown>)[key];
    rejected(r);
  }
});

Deno.test("categoryEnumIsClosed", () => {
  assert.equal(ALERT_CATEGORIES.length, 6);
  for (const category of ALERT_CATEGORIES) {
    const r = report();
    r.alerts[0].category = category;
    assert.equal(validateAlertReport(r, now).alerts[0].category, category);
  }
  for (const bad of ["", "Bullying", "spam", "other", "all", null, 1]) {
    const r = report();
    (r.alerts[0] as Record<string, unknown>).category = bad;
    rejected(r);
  }
});

Deno.test("boundsFieldTypes", () => {
  const mutate = (key: string, bad: unknown) => {
    const r = report();
    (r.alerts[0] as Record<string, unknown>)[key] = bad;
    rejected(r);
  };
  mutate("id", "not-a-uuid");
  mutate("id", "");
  mutate("appLabel", "");
  mutate("appLabel", "x".repeat(65));
  mutate("appLabel", "bad\u0000label");
  mutate("appLabel", "line\nbreak");
  mutate("ruleSetVersion", 0);
  mutate("ruleSetVersion", 1.5);
  mutate("ruleSetVersion", "1");
  mutate("savedContact", "yes");
  mutate("savedContact", 1);
  mutate("savedContact", null);
  mutate("at", "2026-10-10 17:41:00");
  mutate("at", "2026-02-30T00:00:00Z");
  const edge = report();
  edge.alerts[0].appLabel = "x".repeat(64);
  assert.equal(validateAlertReport(edge, now).alerts[0].appLabel.length, 64);
  rejected({ ...report(), notificationAccess: "true" });
  rejected({ ...report(), smsPermission: 0 });
  rejected({ ...report(), version: 2 });
  rejected({ ...report(), sequence: 0 });
  rejected({ ...report(), epochId: "nope" });
});

Deno.test("boundsTimeOrderingAndCount", () => {
  rejected({ ...report(), observedAt: "2026-10-10T18:05:00.001Z" });
  const future = report();
  future.alerts[1].at = "2026-10-10T18:00:01Z";
  rejected(future);
  const old = report();
  old.alerts[0].at = "2026-09-10T17:59:59Z";
  rejected(old);
  const unordered = report();
  unordered.alerts.reverse();
  rejected(unordered);
  const dup = report();
  dup.alerts[1].id = dup.alerts[0].id.toUpperCase();
  rejected(dup);
  const tie = report();
  tie.alerts[1].at = tie.alerts[0].at;
  assert.equal(validateAlertReport(tie, now).alerts.length, 2);
  const many = report();
  many.alerts = Array.from({ length: 50 }, (_, i) => alertAt(i));
  assert.equal(validateAlertReport(many, now).alerts.length, 50);
  many.alerts.push(alertAt(50));
  rejected(many);
  const empty = report();
  empty.alerts = [];
  assert.deepEqual(validateAlertReport(empty, now).alerts, []);
});

Deno.test("clearAndCheckpoint", () => {
  const clear = { version: 1, epochId: fixture.epochId, sequence: 4 };
  assert.deepEqual(validateClearAlerts(clear), clear);
  assert.throws(() => validateClearAlerts({ ...clear, sequence: 0 }));
  assert.throws(() => validateClearAlerts({ ...clear, extra: 1 }));
  assert.deepEqual(validateAlertCheckpointRequest({ version: 1 }), {
    version: 1,
  });
  assert.throws(() => validateAlertCheckpointRequest({ version: 1, a: 1 }));
  assert.deepEqual(
    validateAlertCheckpointReply({ sequence: 0, epochId: null }),
    { sequence: 0, epochId: null },
  );
  assert.throws(() =>
    validateAlertCheckpointReply({ sequence: 0, epochId: fixture.epochId })
  );
  assert.throws(() =>
    validateAlertCheckpointReply({ sequence: 2, epochId: null })
  );
  assert.deepEqual(
    validateAlertWriteReply({
      confirmed: true,
      sequence: 3,
      receivedAt: "2026-10-10T18:00:01Z",
    }),
    { confirmed: true, sequence: 3, receivedAt: "2026-10-10T18:00:01Z" },
  );
  assert.throws(() =>
    validateAlertWriteReply({
      confirmed: false,
      sequence: 3,
      receivedAt: "2026-10-10T18:00:01Z",
    })
  );
});

Deno.test("readRequestPaging", () => {
  assert.deepEqual(
    validateAlertReadRequest({ version: 1, before: null, limit: 50 }),
    { version: 1, before: null, limit: 50 },
  );
  validateAlertReadRequest({
    version: 1,
    before: "2026-10-10T18:00:00Z",
    limit: 1,
  });
  for (const bad of [0, 51, 1.5, -1, "10"]) {
    assert.throws(() =>
      validateAlertReadRequest({ version: 1, before: null, limit: bad })
    );
  }
  assert.throws(() =>
    validateAlertReadRequest({ version: 1, before: "yesterday", limit: 10 })
  );
  assert.throws(() => validateAlertReadRequest({ version: 1, limit: 10 }));
});

Deno.test("readReply", () => {
  const available = (): AlertReadReplyV1 => ({
    state: "available",
    notificationAccess: true,
    smsPermission: false,
    observedAt: "2026-10-10T18:00:00Z",
    receivedAt: "2026-10-10T18:00:02Z",
    alerts: [...report().alerts].reverse(),
    nextBefore: "2026-10-10T17:41:00Z",
  });
  assert.deepEqual(validateAlertReadReply(available()), available());
  const none: AlertReadReplyV1 = {
    state: "none",
    notificationAccess: null,
    smsPermission: null,
    observedAt: null,
    receivedAt: null,
    alerts: [],
    nextBefore: null,
  };
  assert.deepEqual(validateAlertReadReply(none), none);
  assert.deepEqual(
    validateAlertReadReply({ ...none, state: "expired" }),
    { ...none, state: "expired" },
  );
  // Expired or empty replies must not smuggle data.
  assert.throws(() =>
    validateAlertReadReply({ ...none, alerts: report().alerts })
  );
  assert.throws(() => validateAlertReadReply({ ...none, nextBefore: "2026-10-10T17:41:00Z" }));
  assert.throws(() => validateAlertReadReply({ ...none, notificationAccess: true }));
  // Newest first.
  assert.throws(() =>
    validateAlertReadReply({ ...available(), alerts: report().alerts })
  );
  // A cursor with nothing on the page is meaningless.
  assert.throws(() =>
    validateAlertReadReply({ ...available(), alerts: [] })
  );
  assert.throws(() =>
    validateAlertReadReply({ ...available(), unknown: 1 } as unknown)
  );
  const text = JSON.stringify(validateAlertReadReply(available()));
  for (const forbidden of ["text", "sender", "excerpt", "body"]) {
    assert.ok(!text.includes(`"${forbidden}"`));
  }
});
