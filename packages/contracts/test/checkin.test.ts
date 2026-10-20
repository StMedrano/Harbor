import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/checkin-reply-v1.json" with {
  type: "json",
};
import {
  type CheckInReadReplyV1,
  type CheckInReplyInputV1,
  type CheckInStateReplyV1,
  parseCheckInReply,
  parseCheckInRequest,
  validateCheckInReadReply,
  validateCheckInReceipt,
  validateCheckInReply,
  validateCheckInRequestReply,
  validateCheckInStateReply,
  validateCheckInStateRequest,
} from "../src/v1/checkin.ts";
const now = Date.parse("2026-10-20T16:00:00Z");
const reply = (): CheckInReplyInputV1 =>
  structuredClone(fixture) as CheckInReplyInputV1;
const rejected = (v: unknown) =>
  assert.throws(() => validateCheckInReply(v, now));
const id = "5b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74";
const id2 = "6c9e3d51-8f2a-4b74-8a16-2d4f7b9c3e85";

Deno.test("crossLanguageRoundTrip", () => {
  assert.deepEqual(validateCheckInReply(reply(), now), fixture);
  assert.deepEqual(parseCheckInReply(JSON.stringify(fixture), now), fixture);
});
Deno.test("replyHasOnlyPresetAndOptionalFix", () => {
  for (
    const key of [
      "text",
      "message",
      "note",
      "body",
      "deviceId",
      "familyId",
      "childId",
      "sos",
      "help",
    ]
  ) rejected({ ...reply(), [key]: "x" });
  for (const bad of ["help", "OK", "", null, 1, "sos", "emergency"]) {
    rejected({ ...reply(), preset: bad });
  }
  const noFix = { ...reply(), fix: null };
  assert.equal(validateCheckInReply(noFix, now).fix, null);
  const missing = reply() as Record<string, unknown>;
  delete missing.fix;
  rejected(missing);
  assert.throws(() => parseCheckInReply(" ".repeat(4_097), now));
  rejected({ ...reply(), requestId: "nope" });
  rejected({ ...reply(), version: 2 });
});
Deno.test("fixIsBoundedAndRecent", () => {
  const bad = (patch: Record<string, unknown>) => {
    const r = reply();
    Object.assign(r.fix as object, patch);
    rejected(r);
  };
  bad({ latitude: 90.000001 });
  bad({ longitude: -180.5 });
  bad({ latitude: 41.8781141 });
  bad({ accuracyM: -1 });
  bad({ accuracyM: 1.5 });
  bad({ at: "2026-10-20T16:05:01Z" });
  bad({ at: "2026-10-20T14:59:59Z" });
  bad({ address: "1 Main St" });
  const edge = reply();
  edge.fix = { at: "2026-10-20T15:00:00Z", latitude: -90, longitude: 180, accuracyM: 0 };
  assert.equal(validateCheckInReply(edge, now).fix?.latitude, -90);
});
Deno.test("requestInputsTargetOnlyADevice", () => {
  assert.deepEqual(
    parseCheckInRequest(JSON.stringify({ version: 1, deviceId: id })),
    { version: 1, deviceId: id },
  );
  assert.throws(() => parseCheckInRequest(JSON.stringify({ version: 1 })));
  assert.throws(() =>
    parseCheckInRequest(JSON.stringify({ version: 1, deviceId: id, note: "hi" }))
  );
  assert.throws(() =>
    parseCheckInRequest(JSON.stringify({ version: 1, deviceId: "x" }))
  );
  assert.deepEqual(validateCheckInStateRequest({ version: 1 }), { version: 1 });
  assert.throws(() => validateCheckInStateRequest({ version: 1, a: 1 }));
});
Deno.test("requestReceiptAndRepliesAreStrict", () => {
  const ok = {
    requestId: id,
    requestedAt: "2026-10-20T15:50:00Z",
    expiresAt: "2026-10-21T15:50:00Z",
    created: true,
  };
  assert.deepEqual(validateCheckInRequestReply(ok), ok);
  assert.throws(() =>
    validateCheckInRequestReply({ ...ok, expiresAt: "2026-10-22T15:50:00Z" })
  );
  assert.throws(() =>
    validateCheckInRequestReply({ ...ok, expiresAt: "2026-10-20T15:50:00Z" })
  );
  assert.deepEqual(
    validateCheckInReceipt({ confirmed: true, receivedAt: "2026-10-20T16:00:01Z" }),
    { confirmed: true, receivedAt: "2026-10-20T16:00:01Z" },
  );
  assert.throws(() =>
    validateCheckInReceipt({ confirmed: false, receivedAt: "2026-10-20T16:00:01Z" })
  );
});
Deno.test("childStateShowsPendingAndNewestFirstRecent", () => {
  const state: CheckInStateReplyV1 = {
    pending: {
      requestId: id,
      requestedAt: "2026-10-20T15:50:00Z",
      expiresAt: "2026-10-21T15:50:00Z",
    },
    recent: [
      { requestId: id2, requestedAt: "2026-10-19T20:00:00Z", repliedAt: "2026-10-19T20:05:00Z", preset: "ok", located: true },
      { requestId: "7d0f4e62-9a3b-4c85-9b27-3e5a8c0d4f96", requestedAt: "2026-10-18T12:00:00Z", repliedAt: "2026-10-18T12:02:00Z", preset: "late", located: false },
    ],
  };
  assert.deepEqual(validateCheckInStateReply(state), state);
  assert.deepEqual(
    validateCheckInStateReply({ pending: null, recent: [] }),
    { pending: null, recent: [] },
  );
  assert.throws(() =>
    validateCheckInStateReply({ ...state, recent: [...state.recent].reverse() })
  );
  assert.throws(() =>
    validateCheckInStateReply({
      ...state,
      recent: Array.from({ length: 11 }, () => state.recent[0]),
    })
  );
  // Nothing about a recent entry may carry a place or any words.
  const leaky = structuredClone(state) as Record<string, any>;
  leaky.recent[0].latitude = 1;
  assert.throws(() => validateCheckInStateReply(leaky));
});
Deno.test("parentReadIsStatusConsistentAndNewestFirst", () => {
  const read: CheckInReadReplyV1 = {
    checkins: [
      { requestId: id, requestedAt: "2026-10-20T15:50:00Z", expiresAt: "2026-10-21T15:50:00Z", status: "pending", reply: null },
      {
        requestId: id2,
        requestedAt: "2026-10-19T20:00:00Z",
        expiresAt: "2026-10-20T20:00:00Z",
        status: "answered",
        reply: { repliedAt: "2026-10-19T20:05:00Z", preset: "ok", fix: { at: "2026-10-19T20:04:00Z", latitude: 41.878114, longitude: -87.629798, accuracyM: 18 } },
      },
      { requestId: "7d0f4e62-9a3b-4c85-9b27-3e5a8c0d4f96", requestedAt: "2026-10-18T12:00:00Z", expiresAt: "2026-10-19T12:00:00Z", status: "expired", reply: null },
    ],
  };
  assert.deepEqual(validateCheckInReadReply(read), read);
  const wrong = structuredClone(read);
  wrong.checkins[0].reply = { repliedAt: "2026-10-20T15:51:00Z", preset: "ok", fix: null };
  assert.throws(() => validateCheckInReadReply(wrong));
  const missing = structuredClone(read);
  missing.checkins[1].reply = null;
  assert.throws(() => validateCheckInReadReply(missing));
  assert.throws(() =>
    validateCheckInReadReply({ checkins: [...read.checkins].reverse() })
  );
  assert.throws(() =>
    validateCheckInReadReply({
      checkins: Array.from({ length: 21 }, () => read.checkins[2]),
    })
  );
});
