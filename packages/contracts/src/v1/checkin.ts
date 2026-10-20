/**
 * Check-ins. A parent asks a child's phone to check in; the child answers with a preset ("ok" or "late") and, when the
 * phone has one, a recent location fix. There is no free text, no SOS and no way for a child to start one unasked.
 */
export type CheckInPresetV1 = "ok" | "late";
export type CheckInStatusV1 = "pending" | "answered" | "expired";
export type CheckInFixV1 = {
  at: string;
  latitude: number;
  longitude: number;
  accuracyM: number;
};
export type CheckInRequestInputV1 = { version: 1; deviceId: string };
export type CheckInRequestReplyV1 = {
  requestId: string;
  requestedAt: string;
  expiresAt: string;
  created: boolean;
};
export type CheckInStateRequestV1 = { version: 1 };
export type CheckInPendingV1 = {
  requestId: string;
  requestedAt: string;
  expiresAt: string;
};
export type CheckInRecentV1 = {
  requestId: string;
  requestedAt: string;
  repliedAt: string;
  preset: CheckInPresetV1;
  located: boolean;
};
export type CheckInStateReplyV1 = {
  pending: CheckInPendingV1 | null;
  recent: CheckInRecentV1[];
};
export type CheckInReplyInputV1 = {
  version: 1;
  requestId: string;
  preset: CheckInPresetV1;
  fix: CheckInFixV1 | null;
};
export type CheckInReplyReceiptV1 = { confirmed: true; receivedAt: string };
export type CheckInReadInputV1 = { version: 1; deviceId: string };
export type CheckInEntryV1 = {
  requestId: string;
  requestedAt: string;
  expiresAt: string;
  status: CheckInStatusV1;
  reply:
    | { repliedAt: string; preset: CheckInPresetV1; fix: CheckInFixV1 | null }
    | null;
};
export type CheckInReadReplyV1 = { checkins: CheckInEntryV1[] };

export const CHECKIN_MAX_BYTES = 4_096;
export const CHECKIN_TTL_MS = 24 * 3_600_000;
export const CHECKIN_RECENT_LIMIT = 10;
export const CHECKIN_READ_LIMIT = 20;
export const CHECKIN_FIX_MAX_AGE_MS = 3_600_000;

export function parseCheckInRequest(raw: string): CheckInRequestInputV1 {
  demand(new TextEncoder().encode(raw).length <= CHECKIN_MAX_BYTES);
  const r = record(JSON.parse(raw), ["version", "deviceId"]);
  demand(r.version === 1);
  uuid(r.deviceId);
  return { version: 1, deviceId: r.deviceId as string };
}
export function parseCheckInRead(raw: string): CheckInReadInputV1 {
  return parseCheckInRequest(raw);
}
export function validateCheckInStateRequest(
  value: unknown,
): CheckInStateRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function parseCheckInReply(
  raw: string,
  nowMs: number,
): CheckInReplyInputV1 {
  demand(new TextEncoder().encode(raw).length <= CHECKIN_MAX_BYTES);
  return validateCheckInReply(JSON.parse(raw), nowMs);
}
export function validateCheckInReply(
  value: unknown,
  nowMs: number,
): CheckInReplyInputV1 {
  demand(Number.isFinite(nowMs));
  const r = record(value, ["version", "requestId", "preset", "fix"]);
  demand(r.version === 1);
  uuid(r.requestId);
  demand(["ok", "late"].includes(String(r.preset)));
  if (r.fix !== null) validateFix(r.fix, nowMs);
  return structuredClone(value) as CheckInReplyInputV1;
}
export function validateCheckInRequestReply(
  value: unknown,
): CheckInRequestReplyV1 {
  const r = record(value, ["requestId", "requestedAt", "expiresAt", "created"]);
  uuid(r.requestId);
  const at = instant(r.requestedAt);
  const exp = instant(r.expiresAt);
  demand(exp > at && exp - at <= CHECKIN_TTL_MS);
  demand(typeof r.created === "boolean");
  return structuredClone(value) as CheckInRequestReplyV1;
}
export function validateCheckInReceipt(value: unknown): CheckInReplyReceiptV1 {
  const r = record(value, ["confirmed", "receivedAt"]);
  demand(r.confirmed === true);
  instant(r.receivedAt);
  return structuredClone(value) as CheckInReplyReceiptV1;
}
export function validateCheckInStateReply(value: unknown): CheckInStateReplyV1 {
  const r = record(value, ["pending", "recent"]);
  if (r.pending !== null) validatePending(r.pending);
  demand(Array.isArray(r.recent) && r.recent.length <= CHECKIN_RECENT_LIMIT);
  let previous = Infinity;
  for (const item of r.recent) {
    const x = record(item, [
      "requestId",
      "requestedAt",
      "repliedAt",
      "preset",
      "located",
    ]);
    uuid(x.requestId);
    const requested = instant(x.requestedAt);
    const replied = instant(x.repliedAt);
    demand(replied >= requested);
    demand(["ok", "late"].includes(String(x.preset)));
    demand(typeof x.located === "boolean");
    demand(replied <= previous); // newest first
    previous = replied;
  }
  return structuredClone(value) as CheckInStateReplyV1;
}
export function validateCheckInReadReply(value: unknown): CheckInReadReplyV1 {
  const r = record(value, ["checkins"]);
  demand(Array.isArray(r.checkins) && r.checkins.length <= CHECKIN_READ_LIMIT);
  let previous = Infinity;
  for (const item of r.checkins) {
    const x = record(item, [
      "requestId",
      "requestedAt",
      "expiresAt",
      "status",
      "reply",
    ]);
    uuid(x.requestId);
    const requested = instant(x.requestedAt);
    const expires = instant(x.expiresAt);
    demand(expires > requested);
    demand(["pending", "answered", "expired"].includes(String(x.status)));
    demand(requested <= previous); // newest first
    previous = requested;
    if (x.status === "answered") {
      const reply = record(x.reply, ["repliedAt", "preset", "fix"]);
      demand(instant(reply.repliedAt) >= requested);
      demand(["ok", "late"].includes(String(reply.preset)));
      if (reply.fix !== null) validateFix(reply.fix, Number.POSITIVE_INFINITY);
    } else demand(x.reply === null);
  }
  return structuredClone(value) as CheckInReadReplyV1;
}

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid check-in contract");
}
function record(value: unknown, keys: string[]): Record<string, unknown> {
  demand(!!value && typeof value === "object" && !Array.isArray(value));
  const r = value as Record<string, unknown>;
  demand(
    Object.keys(r).length === keys.length &&
      keys.every((k) => Object.hasOwn(r, k)),
  );
  return r;
}
function text(value: unknown, max: number): string {
  demand(
    typeof value === "string" && [...value].length <= max && value.length > 0 &&
      !CONTROL.test(value),
  );
  return value;
}
function integer(value: unknown, min: number, max: number): number {
  demand(
    typeof value === "number" && Number.isSafeInteger(value) && value >= min &&
      value <= max,
  );
  return value;
}
function uuid(value: unknown): string {
  const s = text(value, 36);
  demand(UUID.test(s));
  return s;
}
function instant(value: unknown): number {
  const s = text(value, 24);
  demand(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(s));
  const ms = Date.parse(s);
  demand(Number.isFinite(ms));
  demand(
    new Date(ms).toISOString().replace(".000Z", "Z") ===
      s.replace(".000Z", "Z"),
  );
  return ms;
}
function coordinate(value: unknown, limit: number): number {
  demand(
    typeof value === "number" && Number.isFinite(value) &&
      Math.abs(value) <= limit,
  );
  demand(Number(value.toFixed(6)) === value);
  return value;
}
function validateFix(value: unknown, nowMs: number) {
  const f = record(value, ["at", "latitude", "longitude", "accuracyM"]);
  coordinate(f.latitude, 90);
  coordinate(f.longitude, 180);
  integer(f.accuracyM, 0, 100_000);
  const at = instant(f.at);
  if (Number.isFinite(nowMs)) {
    demand(at <= nowMs + 300_000 && at >= nowMs - CHECKIN_FIX_MAX_AGE_MS);
  }
}
function validatePending(value: unknown) {
  const p = record(value, ["requestId", "requestedAt", "expiresAt"]);
  uuid(p.requestId);
  const at = instant(p.requestedAt);
  const exp = instant(p.expiresAt);
  demand(exp > at && exp - at <= CHECKIN_TTL_MS);
}
