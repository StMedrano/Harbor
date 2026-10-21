import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/access-request-v1.json" with { type: "json" };
import {
  type AccessRequestV1,
  parseAccessRequest,
  validateAccessDecision,
  validateAccessDecisionReply,
  validateAccessRequest,
  validateAccessRequestReply,
  validateAccessRequestsRead,
  validateAccessRequestsReply,
} from "../src/v1/access-requests.ts";

const request = (): AccessRequestV1 => structuredClone(fixture) as AccessRequestV1;
const rejected = (f: () => unknown) => assert.throws(f, TypeError);
const item = (at: string, status = "pending", decidedAt: string | null = null) => ({
  requestId: "8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c", reason: "blocked_app", packageName: "com.zhiliaoapp.musically",
  appLabel: "TikTok", requestedAt: at, status, decidedAt,
});

Deno.test("child request: the shared fixture is valid and carries no free text", () => {
  assert.deepEqual(validateAccessRequest(request()), fixture);
  rejected(() => validateAccessRequest({ ...request(), message: "please" }));
  rejected(() => validateAccessRequest({ ...request(), reason: "because" }));
  rejected(() => validateAccessRequest({ ...request(), packageName: "YouTube" }));
  rejected(() => validateAccessRequest({ ...request(), appLabel: "" }));
  rejected(() => validateAccessRequest({ ...request(), appLabel: "x".repeat(65) }));
  rejected(() => validateAccessRequest({ ...request(), appLabel: "You\nTube" }));
  rejected(() => validateAccessRequest({ ...request(), requestId: "not-a-uuid" }));
  rejected(() => parseAccessRequest(JSON.stringify({ ...request(), appLabel: "y" }) + " ".repeat(5000)));
  for (const reason of ["paused", "bedtime", "blocked_app", "limit_reached", "time_up"]) {
    assert.ok(validateAccessRequest({ ...request(), reason }));
  }
});

Deno.test("child reply, parent read and decision", () => {
  assert.ok(validateAccessRequestReply({ requestId: fixture.requestId, status: "limited", requestedAt: "2026-10-21T15:00:00Z" }));
  rejected(() => validateAccessRequestReply({ requestId: fixture.requestId, status: "maybe", requestedAt: "2026-10-21T15:00:00Z" }));
  assert.ok(validateAccessRequestsRead({ version: 1, deviceId: "22222222-2222-4222-8222-222222222222" }));
  rejected(() => validateAccessRequestsRead({ version: 1, deviceId: "x" }));
  assert.ok(validateAccessRequestsReply({ requests: [item("2026-10-21T15:00:00Z"), item("2026-10-21T14:00:00Z", "approved", "2026-10-21T14:01:00Z")] }));
  rejected(() => validateAccessRequestsReply({ requests: [item("2026-10-21T14:00:00Z"), item("2026-10-21T15:00:00Z")] }));
  rejected(() => validateAccessRequestsReply({ requests: [item("2026-10-21T15:00:00Z", "approved", null)] }));
  rejected(() => validateAccessRequestsReply({ requests: [item("2026-10-21T15:00:00Z", "pending", "2026-10-21T15:01:00Z")] }));
  assert.ok(validateAccessDecision({ version: 1, requestId: fixture.requestId, decision: "denied" }));
  rejected(() => validateAccessDecision({ version: 1, requestId: fixture.requestId, decision: "later" }));
  assert.ok(validateAccessDecisionReply({ requestId: fixture.requestId, status: "approved" }));
});
