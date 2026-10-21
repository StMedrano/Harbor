/**
 * A child's request for more time or access, sent from Harbor's block screen, and a parent's answer.
 * A request names only the app (package and label) and why it was blocked; there is no free text, so a child cannot
 * send a message through it and nothing a child typed or read is stored. A parent's yes is a temporary grant in the
 * device's controls (see controls.ts); the answer here only records the decision and tells the phone.
 */
export type AccessReasonV1 = "paused" | "bedtime" | "blocked_app" | "limit_reached" | "time_up";
export const ACCESS_REASONS: readonly AccessReasonV1[] = ["paused", "bedtime", "blocked_app", "limit_reached", "time_up"];
export type AccessStatusV1 = "pending" | "approved" | "denied" | "expired";
export const ACCESS_STATUSES: readonly AccessStatusV1[] = ["pending", "approved", "denied", "expired"];

/** Child → server (signed). [requestId] is chosen by the phone so a retry is the same request. */
export type AccessRequestV1 = { version: 1; requestId: string; reason: AccessReasonV1; packageName: string; appLabel: string };
/**
 * `pending`: the parents were asked (or an open request for this app already exists; [requestId] is that one).
 * `limited`: too many requests in the last hour; nothing was sent.
 */
export type AccessRequestReplyV1 = { requestId: string; status: "pending" | "limited" | AccessStatusV1; requestedAt: string };

/** Parent read of one phone's requests from the last day, newest first. */
export type AccessRequestsReadV1 = { version: 1; deviceId: string };
export type AccessRequestItemV1 = {
  requestId: string;
  reason: AccessReasonV1;
  packageName: string;
  appLabel: string;
  requestedAt: string;
  status: AccessStatusV1;
  decidedAt: string | null;
};
export type AccessRequestsReplyV1 = { requests: AccessRequestItemV1[] };

export type AccessDecisionV1 = { version: 1; requestId: string; decision: "approved" | "denied" };
export type AccessDecisionReplyV1 = { requestId: string; status: "approved" | "denied" };

/** An unanswered request stops counting after an hour; the child can ask again. */
export const ACCESS_REQUEST_TTL_MS = 60 * 60_000;
export const ACCESS_REQUESTS_PER_HOUR = 10;
export const ACCESS_READ_MAX = 50;
export const ACCESS_MAX_BYTES = 4_096;

export function validateAccessRequest(value: unknown): AccessRequestV1 {
  const r = record(value, ["version", "requestId", "reason", "packageName", "appLabel"]);
  demand(r.version === 1);
  uuid(r.requestId);
  reason(r.reason);
  appPackage(r.packageName);
  label(r.appLabel);
  return structuredClone(value) as AccessRequestV1;
}
export function parseAccessRequest(raw: string): AccessRequestV1 {
  demand(new TextEncoder().encode(raw).length <= ACCESS_MAX_BYTES);
  return validateAccessRequest(JSON.parse(raw));
}
export function validateAccessRequestReply(value: unknown): AccessRequestReplyV1 {
  const r = record(value, ["requestId", "status", "requestedAt"]);
  uuid(r.requestId);
  demand(["pending", "limited", ...ACCESS_STATUSES].includes(r.status as string));
  instant(r.requestedAt);
  return structuredClone(value) as AccessRequestReplyV1;
}
export function validateAccessRequestsRead(value: unknown): AccessRequestsReadV1 {
  const r = record(value, ["version", "deviceId"]);
  demand(r.version === 1);
  uuid(r.deviceId);
  return { version: 1, deviceId: r.deviceId as string };
}
export function validateAccessRequestsReply(value: unknown): AccessRequestsReplyV1 {
  const r = record(value, ["requests"]);
  demand(Array.isArray(r.requests) && r.requests.length <= ACCESS_READ_MAX);
  let previous = Infinity;
  for (const item of r.requests) {
    const i = record(item, ["requestId", "reason", "packageName", "appLabel", "requestedAt", "status", "decidedAt"]);
    uuid(i.requestId);
    reason(i.reason);
    appPackage(i.packageName);
    label(i.appLabel);
    const at = instant(i.requestedAt);
    demand(at <= previous); // newest first
    previous = at;
    demand((ACCESS_STATUSES as readonly unknown[]).includes(i.status));
    if (i.status === "approved" || i.status === "denied") instant(i.decidedAt);
    else demand(i.decidedAt === null);
  }
  return structuredClone(value) as AccessRequestsReplyV1;
}
export function validateAccessDecision(value: unknown): AccessDecisionV1 {
  const r = record(value, ["version", "requestId", "decision"]);
  demand(r.version === 1);
  uuid(r.requestId);
  demand(r.decision === "approved" || r.decision === "denied");
  return structuredClone(value) as AccessDecisionV1;
}
export function validateAccessDecisionReply(value: unknown): AccessDecisionReplyV1 {
  const r = record(value, ["requestId", "status"]);
  uuid(r.requestId);
  demand(r.status === "approved" || r.status === "denied");
  return structuredClone(value) as AccessDecisionReplyV1;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid access request contract");
}
function record(value: unknown, keys: string[]): Record<string, unknown> {
  demand(!!value && typeof value === "object" && !Array.isArray(value));
  const r = value as Record<string, unknown>;
  demand(Object.keys(r).length === keys.length && keys.every((k) => Object.hasOwn(r, k)));
  return r;
}
function uuid(value: unknown): string {
  demand(typeof value === "string" && UUID.test(value));
  return value as string;
}
function reason(value: unknown): AccessReasonV1 {
  demand((ACCESS_REASONS as readonly unknown[]).includes(value));
  return value as AccessReasonV1;
}
function appPackage(value: unknown): string {
  demand(typeof value === "string" && value.length <= 255 && PACKAGE.test(value));
  return value as string;
}
function label(value: unknown): string {
  demand(typeof value === "string" && value.length > 0 && [...value].length <= 64 && !CONTROL.test(value));
  return value as string;
}
function instant(value: unknown): number {
  demand(typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value));
  const ms = Date.parse(value as string);
  demand(Number.isFinite(ms));
  demand(new Date(ms).toISOString().replace(".000Z", "Z") === (value as string).replace(".000Z", "Z"));
  return ms;
}
