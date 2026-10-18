export type UsagePermissionV1 = "granted" | "denied" | "unavailable";
export type InventoryStatusV1 = "complete" | "truncated" | "unavailable";
export type UsageQualityV1 = "partial" | "observed" | "unavailable";
export type UsageAppV1 = { packageName: string; foregroundMs: number };
export type UsageDayV1 = {
  localDate: string;
  startAt: string;
  endAt: string;
  observedThrough: string;
  coverageStart: string | null;
  quality: UsageQualityV1;
  totalMs: number | null;
  apps: UsageAppV1[];
};
export type UsageReportV1 = {
  version: 1;
  epochId: string;
  sequence: number;
  observedAt: string;
  zoneId: string;
  usagePermission: UsagePermissionV1;
  inventoryStatus: InventoryStatusV1;
  inventory: { packageName: string; label: string }[];
  days: UsageDayV1[];
};
export type ClearUsageV1 = { version: 1; epochId: string; sequence: number };
export type UsageWriteReplyV1 = {
  confirmed: true;
  sequence: number;
  receivedAt: string;
};
export type UsageReadReplyV1 = {
  state: "available" | "none" | "expired";
  report: UsageReportV1 | null;
  receivedAt: string | null;
};
export type UsageCheckpointRequestV1 = { version: 1 };
export type UsageCheckpointReplyV1 = {
  sequence: number;
  epochId: string | null;
};
export function validateUsageReport(
  value: unknown,
  nowMs: number,
): UsageReportV1 {
  demand(new TextEncoder().encode(JSON.stringify(value)).length <= LIMIT);
  return validateReport(value, nowMs);
}
export function parseUsageReport(raw: string, nowMs: number): UsageReportV1 {
  demand(new TextEncoder().encode(raw).length <= LIMIT);
  return validateUsageReport(JSON.parse(raw), nowMs);
}
export function validateClearUsage(value: unknown): ClearUsageV1 {
  const r = record(value, ["version", "epochId", "sequence"]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  return structuredClone(value) as ClearUsageV1;
}
export function validateUsageCheckpointRequest(
  value: unknown,
): UsageCheckpointRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function validateUsageCheckpointReply(
  value: unknown,
): UsageCheckpointReplyV1 {
  const r = record(value, ["sequence", "epochId"]);
  const n = integer(r.sequence);
  if (n === 0) demand(r.epochId === null);
  else uuid(r.epochId);
  return structuredClone(value) as UsageCheckpointReplyV1;
}

const LIMIT = 1_048_576;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid usage reporting contract");
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
function integer(value: unknown, min = 0): number {
  demand(
    typeof value === "number" && Number.isSafeInteger(value) && value >= min,
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
function packageName(value: unknown): string {
  const s = text(value, 255);
  demand(PACKAGE.test(s));
  return s;
}
function list(value: unknown, max: number): unknown[] {
  demand(Array.isArray(value) && value.length <= max);
  return value;
}
function unique(values: string[]) {
  demand(new Set(values).size === values.length);
}
function formatter(zone: unknown) {
  const z = text(zone, 100);
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: z,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}
function dateAt(f: Intl.DateTimeFormat, ms: number): string {
  const p = f.formatToParts(ms);
  const get = (k: string) => p.find((x) => x.type === k)!.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}
function calendarMs(day: string): number {
  demand(/^\d{4}-\d{2}-\d{2}$/.test(day));
  const n = Date.parse(day + "T00:00:00Z");
  demand(Number.isFinite(n) && new Date(n).toISOString().slice(0, 10) === day);
  return n;
}
function validateReport(value: unknown, nowMs: number): UsageReportV1 {
  demand(Number.isFinite(nowMs));
  const r = record(value, [
    "version",
    "epochId",
    "sequence",
    "observedAt",
    "zoneId",
    "usagePermission",
    "inventoryStatus",
    "inventory",
    "days",
  ]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  const observed = instant(r.observedAt);
  demand(observed <= nowMs + 300000);
  const f = formatter(r.zoneId);
  const today = calendarMs(dateAt(f, observed));
  demand(
    ["granted", "denied", "unavailable"].includes(String(r.usagePermission)),
  );
  demand(
    ["complete", "truncated", "unavailable"].includes(
      String(r.inventoryStatus),
    ),
  );
  const inventory = list(r.inventory, 500);
  const names = inventory.map((v) => {
    const i = record(v, ["packageName", "label"]);
    text(i.label, 200);
    return packageName(i.packageName);
  });
  unique(names);
  if (r.inventoryStatus === "unavailable") demand(inventory.length === 0);
  const days = list(r.days, 7);
  let appCount = 0;
  const dates = days.map((v) => {
    const d = record(v, [
      "localDate",
      "startAt",
      "endAt",
      "observedThrough",
      "coverageStart",
      "quality",
      "totalMs",
      "apps",
    ]);
    const day = text(d.localDate, 10), dayMs = calendarMs(day);
    demand(dayMs <= today && today - dayMs <= 6 * 86400000);
    const start = instant(d.startAt),
      end = instant(d.endAt),
      through = instant(d.observedThrough);
    demand(
      start < end && through >= start && through <= end && through <= observed,
    );
    demand(
      dateAt(f, start) === day && dateAt(f, start - 1) !== day &&
        dateAt(f, end - 1) === day && dateAt(f, end) !== day,
    );
    demand(calendarMs(dateAt(f, end)) === dayMs + 86400000);
    demand(["partial", "observed", "unavailable"].includes(String(d.quality)));
    const apps = list(d.apps, 3500);
    appCount += apps.length;
    demand(appCount <= 3500);
    if (d.quality === "unavailable" || d.coverageStart === null) {
      demand(
        d.totalMs === null && d.coverageStart === null && apps.length === 0 &&
          d.quality !== "observed",
      );
    } else {
      const coverage = instant(d.coverageStart);
      demand(coverage >= start && coverage <= through);
      if (d.quality === "observed") demand(coverage === start);
      const elapsed = through - coverage;
      demand(integer(d.totalMs) <= elapsed);
      unique(apps.map((a) => {
        const x = record(a, ["packageName", "foregroundMs"]);
        demand(integer(x.foregroundMs) <= elapsed);
        return packageName(x.packageName);
      }));
    }
    return day;
  });
  unique(dates);
  return structuredClone(value) as UsageReportV1;
}
