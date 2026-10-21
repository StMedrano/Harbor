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
/**
 * Total screen time a day, a hard stop: when it is used up, only always-allowed ("Unlimited") apps and the phone's
 * exempt apps work until midnight. Always-allowed apps do not count toward it. A parent can add extra minutes for one
 * day; `extraDate` is the child phone's local date they apply to.
 */
export type DailyLimitV1 = { minutes: number; extraDate: string | null; extraMinutes: number };
/**
 * A parent's yes to a child's request: [packageName] may be used until [until], whatever pause, bedtime, block or limit
 * applies. Grants that have ended are dropped the next time a parent changes controls.
 */
export type GrantV1 = { packageName: string; until: string };
export type ControlsV1 = {
  version: 1;
  pause: ControlsPauseV1;
  bedtime: ControlsBedtimeV1 | null;
  blockedApps: string[];
  appLimits: AppLimitV1[];
  alwaysAllowed: string[];
  dailyLimit: DailyLimitV1 | null;
  grants: GrantV1[];
};

export const CONTROLS_MAX_BLOCKED = 200;
export const CONTROLS_MAX_LIMITS = 200;
export const CONTROLS_MAX_ALLOWED = 50;
export const CONTROLS_MAX_GRANTS = 20;
export const CONTROLS_MAX_BYTES = 32_768;
/** Keys added after the first release; a policy written before them omits them and reads as their empty value. */
const LATER_KEYS = ["dailyLimit", "grants"] as const;

/** The policy a device has before a parent sets anything: nothing is blocked. */
export function emptyControls(): ControlsV1 {
  return { version: 1, pause: { active: false, until: null }, bedtime: null, blockedApps: [], appLimits: [], alwaysAllowed: [], dailyLimit: null, grants: [] };
}

export function validateControls(value: unknown): ControlsV1 {
  demand(new TextEncoder().encode(JSON.stringify(value) ?? "").length <= CONTROLS_MAX_BYTES);
  const present = !!value && typeof value === "object" && !Array.isArray(value)
    ? LATER_KEYS.filter((k) => Object.hasOwn(value, k))
    : [];
  const r = record(value, ["version", "pause", "bedtime", "blockedApps", "appLimits", "alwaysAllowed", ...present]);
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
  if (r.dailyLimit !== undefined && r.dailyLimit !== null) {
    const d = record(r.dailyLimit, ["minutes", "extraDate", "extraMinutes"]);
    demand(typeof d.minutes === "number" && Number.isSafeInteger(d.minutes) && d.minutes >= 1 && d.minutes <= 1440);
    demand(typeof d.extraMinutes === "number" && Number.isSafeInteger(d.extraMinutes) && d.extraMinutes >= 0 && d.extraMinutes <= 720);
    if (d.extraDate === null) demand(d.extraMinutes === 0);
    else localDate(d.extraDate);
  }
  if (r.grants !== undefined) {
    demand(Array.isArray(r.grants) && r.grants.length <= CONTROLS_MAX_GRANTS);
    const granted = new Set<string>();
    for (const item of r.grants) {
      const g = record(item, ["packageName", "until"]);
      const name = validatePackageName(g.packageName);
      demand(!granted.has(name));
      granted.add(name);
      instant(g.until);
    }
  }
  const out = structuredClone(value) as ControlsV1;
  if (r.dailyLimit === undefined) out.dailyLimit = null;
  if (r.grants === undefined) out.grants = [];
  return out;
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
function localDate(value: unknown): string {
  demand(typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value));
  const ms = Date.parse(`${value}T00:00:00Z`);
  demand(Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 10) === value);
  return value as string;
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

/** Parent read of one device's controls. `controls` is null until a parent has set any. */
export type DeviceControlsRequestV1 = { version: 1; deviceId: string };
export type DeviceControlsReplyV1 = { controls: ControlsV1 | null; desiredStateVersion: number; acknowledgedVersion: number };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function validateDeviceControlsRequest(value: unknown): DeviceControlsRequestV1 {
  const r = record(value, ["version", "deviceId"]);
  demand(r.version === 1 && typeof r.deviceId === "string" && UUID.test(r.deviceId));
  return { version: 1, deviceId: r.deviceId as string };
}
export function validateDeviceControlsReply(value: unknown): DeviceControlsReplyV1 {
  const r = record(value, ["controls", "desiredStateVersion", "acknowledgedVersion"]);
  if (r.controls !== null) validateControls(r.controls);
  for (const v of [r.desiredStateVersion, r.acknowledgedVersion]) {
    demand(typeof v === "number" && Number.isSafeInteger(v) && v >= 0);
  }
  demand((r.acknowledgedVersion as number) <= (r.desiredStateVersion as number));
  return structuredClone(value) as DeviceControlsReplyV1;
}
