/**
 * App controls. A parent sets these on one child device; they travel inside the device's desired state as
 * `desiredState.controls` and the child phone enforces them. Times are wall-clock times on the child phone.
 * Nothing here names a website, a contact or any content: controls work on whole apps only.
 */
export type WeekdayV1 = "mon" | "tue" | "wed" | "thu" | "fri" | "sat" | "sun";
export const WEEKDAYS: readonly WeekdayV1[] = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
export type ControlsPauseV1 = { active: boolean; until: string | null };
export type ControlsBedtimeV1 = { enabled: boolean; start: string; end: string; days: WeekdayV1[] };
export type AppLimitV1 = { packageName: string; minutesPerDay: number };
export type ControlsV1 = {
  version: 1;
  pause: ControlsPauseV1;
  bedtime: ControlsBedtimeV1 | null;
  blockedApps: string[];
  appLimits: AppLimitV1[];
  alwaysAllowed: string[];
};

export const CONTROLS_MAX_BLOCKED = 200;
export const CONTROLS_MAX_LIMITS = 200;
export const CONTROLS_MAX_ALLOWED = 50;
export const CONTROLS_MAX_BYTES = 32_768;

/** The policy a device has before a parent sets anything: nothing is blocked. */
export function emptyControls(): ControlsV1 {
  return { version: 1, pause: { active: false, until: null }, bedtime: null, blockedApps: [], appLimits: [], alwaysAllowed: [] };
}

export function validateControls(value: unknown): ControlsV1 {
  demand(new TextEncoder().encode(JSON.stringify(value) ?? "").length <= CONTROLS_MAX_BYTES);
  const r = record(value, ["version", "pause", "bedtime", "blockedApps", "appLimits", "alwaysAllowed"]);
  demand(r.version === 1);
  const pause = record(r.pause, ["active", "until"]);
  demand(typeof pause.active === "boolean");
  if (pause.until !== null) {
    demand(pause.active === true);
    instant(pause.until);
  }
  if (r.bedtime !== null) {
    const b = record(r.bedtime, ["enabled", "start", "end", "days"]);
    demand(typeof b.enabled === "boolean");
    clock(b.start);
    clock(b.end);
    demand(b.start !== b.end);
    demand(Array.isArray(b.days) && b.days.length <= 7);
    demand(new Set(b.days).size === b.days.length);
    for (const d of b.days) demand((WEEKDAYS as readonly unknown[]).includes(d));
    if (b.enabled) demand(b.days.length > 0);
  }
  const blocked = packages(r.blockedApps, CONTROLS_MAX_BLOCKED);
  const allowed = packages(r.alwaysAllowed, CONTROLS_MAX_ALLOWED);
  demand(Array.isArray(r.appLimits) && r.appLimits.length <= CONTROLS_MAX_LIMITS);
  const limited = new Set<string>();
  for (const item of r.appLimits) {
    const l = record(item, ["packageName", "minutesPerDay"]);
    const name = validatePackageName(l.packageName);
    demand(!limited.has(name));
    limited.add(name);
    demand(typeof l.minutesPerDay === "number" && Number.isSafeInteger(l.minutesPerDay) && l.minutesPerDay >= 1 && l.minutesPerDay <= 1440);
  }
  // One rule per app: an app is blocked, limited, always allowed, or none of these.
  for (const name of blocked) demand(!allowed.has(name) && !limited.has(name));
  for (const name of limited) demand(!allowed.has(name));
  return structuredClone(value) as ControlsV1;
}

const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const CLOCK = /^([01]\d|2[0-3]):[0-5]\d$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid app controls contract");
}
function record(value: unknown, keys: string[]): Record<string, unknown> {
  demand(!!value && typeof value === "object" && !Array.isArray(value));
  const r = value as Record<string, unknown>;
  demand(Object.keys(r).length === keys.length && keys.every((k) => Object.hasOwn(r, k)));
  return r;
}
export function validatePackageName(value: unknown): string {
  demand(typeof value === "string" && value.length <= 255 && PACKAGE.test(value));
  return value as string;
}
function packages(value: unknown, max: number): Set<string> {
  demand(Array.isArray(value) && value.length <= max);
  const seen = new Set<string>();
  for (const v of value) {
    const name = validatePackageName(v);
    demand(!seen.has(name));
    seen.add(name);
  }
  return seen;
}
function clock(value: unknown): string {
  demand(typeof value === "string" && CLOCK.test(value));
  return value as string;
}
function instant(value: unknown): number {
  demand(typeof value === "string" && INSTANT.test(value));
  const ms = Date.parse(value as string);
  demand(Number.isFinite(ms));
  demand(new Date(ms).toISOString().replace(".000Z", "Z") === (value as string).replace(".000Z", "Z"));
  return ms;
}
