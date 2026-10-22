/**
 * Device activity. The child phone reports what apps were used and when, and what the app controls did.
 * An event names an app (package and label), times and, for blocks, a reason. There is deliberately no field for
 * screen content, window titles, web addresses or any text the child typed or read; unknown keys are rejected.
 */
export type ActivityKindV1 =
  | "app_session"
  | "blocked_open"
  | "limit_reached"
  | "pause_started"
  | "pause_ended"
  | "bedtime_started"
  | "bedtime_ended"
  | "protection_off"
  | "protection_on";
export const ACTIVITY_KINDS: readonly ActivityKindV1[] = [
  "app_session",
  "blocked_open",
  "limit_reached",
  "pause_started",
  "pause_ended",
  "bedtime_started",
  "bedtime_ended",
  "protection_off",
  "protection_on",
];
export type BlockReasonV1 = "paused" | "bedtime" | "blocked_app" | "limit_reached";
export const BLOCK_REASONS: readonly BlockReasonV1[] = ["paused", "bedtime", "blocked_app", "limit_reached"];
/** Kinds that are about one app carry its package and label; the others carry neither. */
export const APP_KINDS: readonly ActivityKindV1[] = ["app_session", "blocked_open", "limit_reached"];
export type ActivityEventV1 = {
  id: string;
  kind: ActivityKindV1;
  at: string;
  endAt: string | null;
  packageName: string | null;
  appLabel: string | null;
  reason: BlockReasonV1 | null;
};
/**
 * Today's time on screen as the child phone counts it for its limits: the phone's local [date], the total that counts
 * toward the daily limit, and the time of up to 50 apps. App names and minutes only. Optional: older phones omit it.
 */
export type ScreenTimeV1 = { date: string; totalMs: number; apps: { packageName: string; ms: number }[] };
export type ActivityStatusV1 = { controlsOn: boolean; policyVersion: number; screenTime?: ScreenTimeV1 };
export type ActivityReportV1 = {
  version: 1;
  epochId: string;
  sequence: number;
  observedAt: string;
  status: ActivityStatusV1;
  events: ActivityEventV1[];
};
export type ClearActivityV1 = { version: 1; epochId: string; sequence: number };
export type ActivityWriteReplyV1 = { confirmed: true; sequence: number; receivedAt: string };
export type ActivityCheckpointRequestV1 = { version: 1 };
export type ActivityCheckpointReplyV1 = { sequence: number; epochId: string | null };
export type ActivityReadRequestV1 = { version: 1; from: string; to: string };
export type ActivityReadReplyV1 = {
  state: "available" | "none" | "expired";
  controlsOn: boolean | null;
  policyVersion: number | null;
  /** The phone's latest screen-time count, or null when it has not sent one. */
  screenTime: ScreenTimeV1 | null;
  observedAt: string | null;
  receivedAt: string | null;
  events: ActivityEventV1[];
  truncated: boolean;
};

export const ACTIVITY_MAX_BYTES = 65_536;
export const ACTIVITY_MAX_PER_REPORT = 100;
export const ACTIVITY_MAX_READ = 500;
export const ACTIVITY_MAX_WINDOW_MS = 48 * 3_600_000;
export const ACTIVITY_RETENTION_MS = 30 * 86_400_000;
export const ACTIVITY_MIN_SESSION_MS = 60_000;
export const ACTIVITY_MAX_SESSION_MS = 24 * 3_600_000;
export const ACTIVITY_LABEL_MAX = 64;

export function validateActivityReport(value: unknown, nowMs: number): ActivityReportV1 {
  demand(new TextEncoder().encode(JSON.stringify(value)).length <= ACTIVITY_MAX_BYTES);
  demand(Number.isFinite(nowMs));
  const r = record(value, ["version", "epochId", "sequence", "observedAt", "status", "events"]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  const observed = instant(r.observedAt);
  demand(observed <= nowMs + 300_000);
  validateStatus(r.status);
  demand(Array.isArray(r.events) && r.events.length <= ACTIVITY_MAX_PER_REPORT);
  const ids = new Set<string>();
  let previous = -Infinity;
  for (const e of r.events) {
    const at = validateActivityEvent(e);
    demand(at <= observed && at >= observed - ACTIVITY_RETENTION_MS);
    demand(at >= previous); // oldest first
    previous = at;
    const id = (e as ActivityEventV1).id.toLowerCase();
    demand(!ids.has(id));
    ids.add(id);
  }
  return structuredClone(value) as ActivityReportV1;
}
export function parseActivityReport(raw: string, nowMs: number): ActivityReportV1 {
  demand(new TextEncoder().encode(raw).length <= ACTIVITY_MAX_BYTES);
  return validateActivityReport(JSON.parse(raw), nowMs);
}
export function validateClearActivity(value: unknown): ClearActivityV1 {
  const r = record(value, ["version", "epochId", "sequence"]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  return structuredClone(value) as ClearActivityV1;
}
export function validateActivityCheckpointRequest(value: unknown): ActivityCheckpointRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function validateActivityCheckpointReply(value: unknown): ActivityCheckpointReplyV1 {
  const r = record(value, ["sequence", "epochId"]);
  const n = integer(r.sequence);
  if (n === 0) demand(r.epochId === null);
  else uuid(r.epochId);
  return structuredClone(value) as ActivityCheckpointReplyV1;
}
export function validateActivityReadRequest(value: unknown): ActivityReadRequestV1 {
  const r = record(value, ["version", "from", "to"]);
  demand(r.version === 1);
  const from = instant(r.from);
  const to = instant(r.to);
  demand(from < to && to - from <= ACTIVITY_MAX_WINDOW_MS);
  return structuredClone(value) as ActivityReadRequestV1;
}
export function validateActivityWriteReply(value: unknown): ActivityWriteReplyV1 {
  const r = record(value, ["confirmed", "sequence", "receivedAt"]);
  demand(r.confirmed === true);
  integer(r.sequence, 1);
  instant(r.receivedAt);
  return structuredClone(value) as ActivityWriteReplyV1;
}
export function validateActivityReadReply(value: unknown): ActivityReadReplyV1 {
  const r = record(value, ["state", "controlsOn", "policyVersion", "screenTime", "observedAt", "receivedAt", "events", "truncated"]);
  if (r.screenTime !== null) validateScreenTime(r.screenTime);
  demand(["available", "none", "expired"].includes(String(r.state)));
  demand(typeof r.truncated === "boolean");
  demand(Array.isArray(r.events) && r.events.length <= ACTIVITY_MAX_READ);
  if (r.state === "available") {
    demand(typeof r.controlsOn === "boolean");
    integer(r.policyVersion);
    instant(r.observedAt);
    instant(r.receivedAt);
  } else {
    demand(r.controlsOn === null && r.policyVersion === null && r.screenTime === null && r.observedAt === null && r.receivedAt === null);
    demand(r.events.length === 0 && r.truncated === false);
  }
  let previous = -Infinity;
  for (const e of r.events) {
    const at = validateActivityEvent(e);
    demand(at >= previous); // oldest first
    previous = at;
  }
  return structuredClone(value) as ActivityReadReplyV1;
}

/** Returns the event time in epoch milliseconds after checking every field. */
export function validateActivityEvent(value: unknown): number {
  const e = record(value, ["id", "kind", "at", "endAt", "packageName", "appLabel", "reason"]);
  uuid(e.id);
  demand((ACTIVITY_KINDS as readonly unknown[]).includes(e.kind));
  const at = instant(e.at);
  const kind = e.kind as ActivityKindV1;
  if ((APP_KINDS as readonly string[]).includes(kind)) {
    appPackage(e.packageName);
    text(e.appLabel, ACTIVITY_LABEL_MAX);
  } else {
    demand(e.packageName === null && e.appLabel === null);
  }
  if (kind === "app_session") {
    const end = instant(e.endAt);
    demand(end - at >= ACTIVITY_MIN_SESSION_MS && end - at <= ACTIVITY_MAX_SESSION_MS);
  } else {
    demand(e.endAt === null);
  }
  if (kind === "blocked_open") demand((BLOCK_REASONS as readonly unknown[]).includes(e.reason));
  else demand(e.reason === null);
  return at;
}

export const SCREEN_TIME_MAX_APPS = 50;
const DAY_MS = 86_400_000;
export function validateScreenTime(value: unknown): ScreenTimeV1 {
  const t = record(value, ["date", "totalMs", "apps"]);
  demand(typeof t.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(t.date));
  demand(new Date(Date.parse(`${t.date}T00:00:00Z`)).toISOString().slice(0, 10) === t.date);
  integer(t.totalMs, 0, DAY_MS);
  demand(Array.isArray(t.apps) && t.apps.length <= SCREEN_TIME_MAX_APPS);
  const seen = new Set<string>();
  for (const item of t.apps) {
    const a = record(item, ["packageName", "ms"]);
    demand(!seen.has(appPackage(a.packageName)));
    seen.add(a.packageName as string);
    integer(a.ms, 0, DAY_MS);
  }
  return t as ScreenTimeV1;
}
function validateStatus(value: unknown): ActivityStatusV1 {
  const hasScreenTime = !!value && typeof value === "object" && Object.hasOwn(value, "screenTime");
  const s = record(value, ["controlsOn", "policyVersion", ...(hasScreenTime ? ["screenTime"] : [])]);
  if (hasScreenTime) validateScreenTime(s.screenTime);
  demand(typeof s.controlsOn === "boolean");
  integer(s.policyVersion);
  return s as ActivityStatusV1;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid device activity contract");
}
function record(value: unknown, keys: string[]): Record<string, unknown> {
  demand(!!value && typeof value === "object" && !Array.isArray(value));
  const r = value as Record<string, unknown>;
  demand(Object.keys(r).length === keys.length && keys.every((k) => Object.hasOwn(r, k)));
  return r;
}
function text(value: unknown, max: number): string {
  demand(typeof value === "string" && [...value].length <= max && value.length > 0 && !CONTROL.test(value));
  return value as string;
}
function integer(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  demand(typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max);
  return value as number;
}
function uuid(value: unknown): string {
  const s = text(value, 36);
  demand(UUID.test(s));
  return s;
}
function appPackage(value: unknown): string {
  demand(typeof value === "string" && value.length <= 255 && PACKAGE.test(value));
  return value as string;
}
function instant(value: unknown): number {
  const s = text(value, 24);
  demand(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(s));
  const ms = Date.parse(s);
  demand(Number.isFinite(ms));
  demand(new Date(ms).toISOString().replace(".000Z", "Z") === s.replace(".000Z", "Z"));
  return ms;
}
