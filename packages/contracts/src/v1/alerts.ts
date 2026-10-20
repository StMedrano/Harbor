/**
 * Message-concern alerts. An alert says THAT something was flagged (a category, the app it
 * came from and when). It never carries message text, a sender, a number, a name or an
 * excerpt: those fields do not exist in this contract and unknown keys are rejected.
 */
export type AlertCategoryV1 =
  | "bullying"
  | "self_harm"
  | "adult_content"
  | "drugs_alcohol"
  | "violence_threat"
  | "unknown_adult_contact";
export const ALERT_CATEGORIES: readonly AlertCategoryV1[] = [
  "bullying",
  "self_harm",
  "adult_content",
  "drugs_alcohol",
  "violence_threat",
  "unknown_adult_contact",
];
export type AlertV1 = {
  id: string;
  appLabel: string;
  category: AlertCategoryV1;
  ruleSetVersion: number;
  at: string;
  savedContact: boolean;
};
export type AlertReportV1 = {
  version: 1;
  epochId: string;
  sequence: number;
  observedAt: string;
  notificationAccess: boolean;
  smsPermission: boolean;
  alerts: AlertV1[];
};
export type ClearAlertsV1 = { version: 1; epochId: string; sequence: number };
export type AlertWriteReplyV1 = {
  confirmed: true;
  sequence: number;
  receivedAt: string;
};
export type AlertReadRequestV1 = {
  version: 1;
  before: string | null;
  limit: number;
};
export type AlertReadReplyV1 = {
  state: "available" | "none" | "expired";
  notificationAccess: boolean | null;
  smsPermission: boolean | null;
  observedAt: string | null;
  receivedAt: string | null;
  alerts: AlertV1[];
  nextBefore: string | null;
};
export type AlertCheckpointRequestV1 = { version: 1 };
export type AlertCheckpointReplyV1 = {
  sequence: number;
  epochId: string | null;
};

export const ALERT_MAX_BYTES = 65_536;
export const ALERT_MAX_PER_REPORT = 50;
export const ALERT_MAX_PAGE = 50;
export const ALERT_RETENTION_MS = 30 * 86_400_000;
export const ALERT_LABEL_MAX = 64;

export function validateAlertReport(
  value: unknown,
  nowMs: number,
): AlertReportV1 {
  demand(
    new TextEncoder().encode(JSON.stringify(value)).length <= ALERT_MAX_BYTES,
  );
  return validateReport(value, nowMs);
}
export function parseAlertReport(raw: string, nowMs: number): AlertReportV1 {
  demand(new TextEncoder().encode(raw).length <= ALERT_MAX_BYTES);
  return validateAlertReport(JSON.parse(raw), nowMs);
}
export function validateClearAlerts(value: unknown): ClearAlertsV1 {
  const r = record(value, ["version", "epochId", "sequence"]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  return structuredClone(value) as ClearAlertsV1;
}
export function validateAlertCheckpointRequest(
  value: unknown,
): AlertCheckpointRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function validateAlertCheckpointReply(
  value: unknown,
): AlertCheckpointReplyV1 {
  const r = record(value, ["sequence", "epochId"]);
  const n = integer(r.sequence);
  if (n === 0) demand(r.epochId === null);
  else uuid(r.epochId);
  return structuredClone(value) as AlertCheckpointReplyV1;
}
export function validateAlertReadRequest(value: unknown): AlertReadRequestV1 {
  const r = record(value, ["version", "before", "limit"]);
  demand(r.version === 1);
  if (r.before !== null) instant(r.before);
  integer(r.limit, 1, ALERT_MAX_PAGE);
  return structuredClone(value) as AlertReadRequestV1;
}
export function validateAlertWriteReply(value: unknown): AlertWriteReplyV1 {
  const r = record(value, ["confirmed", "sequence", "receivedAt"]);
  demand(r.confirmed === true);
  integer(r.sequence, 1);
  instant(r.receivedAt);
  return structuredClone(value) as AlertWriteReplyV1;
}
export function validateAlertReadReply(value: unknown): AlertReadReplyV1 {
  const r = record(value, [
    "state",
    "notificationAccess",
    "smsPermission",
    "observedAt",
    "receivedAt",
    "alerts",
    "nextBefore",
  ]);
  demand(["available", "none", "expired"].includes(String(r.state)));
  demand(Array.isArray(r.alerts) && r.alerts.length <= ALERT_MAX_PAGE);
  if (r.state === "available") {
    demand(typeof r.notificationAccess === "boolean");
    demand(typeof r.smsPermission === "boolean");
    instant(r.observedAt);
    instant(r.receivedAt);
  } else {
    demand(r.notificationAccess === null && r.smsPermission === null);
    demand(r.observedAt === null && r.receivedAt === null);
    demand(r.alerts.length === 0 && r.nextBefore === null);
  }
  let previous = Infinity;
  for (const alert of r.alerts) {
    const at = validateAlert(alert);
    demand(at <= previous); // newest first; equal instants are allowed
    previous = at;
  }
  if (r.nextBefore !== null) {
    instant(r.nextBefore);
    demand(r.alerts.length > 0);
  }
  return structuredClone(value) as AlertReadReplyV1;
}

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid message alert contract");
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
function integer(
  value: unknown,
  min = 0,
  max = Number.MAX_SAFE_INTEGER,
): number {
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
function validateAlert(value: unknown): number {
  const a = record(value, [
    "id",
    "appLabel",
    "category",
    "ruleSetVersion",
    "at",
    "savedContact",
  ]);
  uuid(a.id);
  text(a.appLabel, ALERT_LABEL_MAX);
  demand((ALERT_CATEGORIES as readonly string[]).includes(String(a.category)));
  integer(a.ruleSetVersion, 1, 1_000_000);
  demand(typeof a.savedContact === "boolean");
  return instant(a.at);
}
function validateReport(value: unknown, nowMs: number): AlertReportV1 {
  demand(Number.isFinite(nowMs));
  const r = record(value, [
    "version",
    "epochId",
    "sequence",
    "observedAt",
    "notificationAccess",
    "smsPermission",
    "alerts",
  ]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  const observed = instant(r.observedAt);
  demand(observed <= nowMs + 300_000);
  demand(typeof r.notificationAccess === "boolean");
  demand(typeof r.smsPermission === "boolean");
  demand(Array.isArray(r.alerts) && r.alerts.length <= ALERT_MAX_PER_REPORT);
  const ids = new Set<string>();
  let previous = -Infinity;
  for (const alert of r.alerts) {
    const at = validateAlert(alert);
    demand(at <= observed && at >= observed - ALERT_RETENTION_MS);
    demand(at >= previous); // oldest first; equal instants are allowed
    previous = at;
    const id = (alert as AlertV1).id.toLowerCase();
    demand(!ids.has(id));
    ids.add(id);
  }
  return structuredClone(value) as AlertReportV1;
}
