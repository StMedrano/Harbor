/**
 * Family map. A child's phone can see where the family's children are, and where parents are who chose to share.
 * A parent shares only by turning it on in their own app; parents' places are the latest one only. A parent switch
 * decides whether children see the map at all. There are no alerts, no geofences and no history here.
 */
export type FamilyMapFixV1 = {
  at: string;
  latitude: number;
  longitude: number;
  accuracyM: number;
};
export type FamilyMapPersonV1 = {
  id: string;
  kind: "child" | "parent";
  name: string;
  self: boolean;
  fix: FamilyMapFixV1 | null;
};
export type FamilyMapRequestV1 = { version: 1 };
export type FamilyMapReplyV1 = {
  enabled: boolean;
  people: FamilyMapPersonV1[];
};
export type ParentLocationReportV1 = {
  version: 1;
  familyId: string;
  label: string;
  fix: FamilyMapFixV1;
};
export type ParentLocationStopV1 = { version: 1; familyId: string };
export type ParentLocationReceiptV1 = { confirmed: true; receivedAt: string };
export type FamilyMapSettingsRequestV1 = { version: 1; familyId: string };
export type FamilyMapSettingsReplyV1 = {
  childrenCanView: boolean;
  mySharing: { label: string; lastAt: string } | null;
};
export type FamilyMapSettingsUpdateV1 = {
  version: 1;
  familyId: string;
  childrenCanView: boolean;
};

export const FAMILY_MAP_MAX_BYTES = 4_096;
export const FAMILY_MAP_MAX_PEOPLE = 50;
export const FAMILY_MAP_LABEL_MAX = 40;
/** A parent's report must carry a place no older than this. */
export const PARENT_FIX_MAX_AGE_MS = 600_000;
/** A parent's latest place is removed this long after it was taken, even if they never press stop. */
export const PARENT_FIX_RETENTION_MS = 24 * 3_600_000;

export function validateFamilyMapRequest(value: unknown): FamilyMapRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function parseParentLocationReport(
  raw: string,
  nowMs: number,
): ParentLocationReportV1 {
  demand(new TextEncoder().encode(raw).length <= FAMILY_MAP_MAX_BYTES);
  demand(Number.isFinite(nowMs));
  const r = record(JSON.parse(raw), ["version", "familyId", "label", "fix"]);
  demand(r.version === 1);
  uuid(r.familyId);
  label(r.label);
  const at = validateFix(r.fix);
  demand(at <= nowMs + 300_000 && at >= nowMs - PARENT_FIX_MAX_AGE_MS);
  return structuredClone(r) as ParentLocationReportV1;
}
export function parseParentLocationStop(raw: string): ParentLocationStopV1 {
  demand(new TextEncoder().encode(raw).length <= FAMILY_MAP_MAX_BYTES);
  const r = record(JSON.parse(raw), ["version", "familyId"]);
  demand(r.version === 1);
  uuid(r.familyId);
  return { version: 1, familyId: r.familyId as string };
}
export function parseFamilyMapSettingsRequest(
  raw: string,
): FamilyMapSettingsRequestV1 {
  return parseParentLocationStop(raw);
}
export function parseFamilyMapSettingsUpdate(
  raw: string,
): FamilyMapSettingsUpdateV1 {
  demand(new TextEncoder().encode(raw).length <= FAMILY_MAP_MAX_BYTES);
  const r = record(JSON.parse(raw), ["version", "familyId", "childrenCanView"]);
  demand(r.version === 1);
  uuid(r.familyId);
  demand(typeof r.childrenCanView === "boolean");
  return structuredClone(r) as FamilyMapSettingsUpdateV1;
}
export function validateFamilyMapReply(value: unknown): FamilyMapReplyV1 {
  const r = record(value, ["enabled", "people"]);
  demand(typeof r.enabled === "boolean");
  demand(Array.isArray(r.people) && r.people.length <= FAMILY_MAP_MAX_PEOPLE);
  if (!r.enabled) demand(r.people.length === 0);
  const seen = new Set<string>();
  let selves = 0;
  for (const item of r.people) {
    const p = record(item, ["id", "kind", "name", "self", "fix"]);
    const id = uuid(p.id);
    demand(!seen.has(id));
    seen.add(id);
    demand(p.kind === "child" || p.kind === "parent");
    label(p.name);
    demand(typeof p.self === "boolean");
    if (p.self) {
      selves++;
      demand(p.kind === "child");
    }
    if (p.kind === "parent") demand(p.fix !== null);
    if (p.fix !== null) validateFix(p.fix);
  }
  demand(selves <= 1);
  return structuredClone(value) as FamilyMapReplyV1;
}
export function validateParentLocationReceipt(
  value: unknown,
): ParentLocationReceiptV1 {
  const r = record(value, ["confirmed", "receivedAt"]);
  demand(r.confirmed === true);
  instant(r.receivedAt);
  return structuredClone(value) as ParentLocationReceiptV1;
}
export function validateFamilyMapSettingsReply(
  value: unknown,
): FamilyMapSettingsReplyV1 {
  const r = record(value, ["childrenCanView", "mySharing"]);
  demand(typeof r.childrenCanView === "boolean");
  if (r.mySharing !== null) {
    const m = record(r.mySharing, ["label", "lastAt"]);
    label(m.label);
    instant(m.lastAt);
  }
  return structuredClone(value) as FamilyMapSettingsReplyV1;
}

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid family map contract");
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
function label(value: unknown): string {
  const s = text(value, FAMILY_MAP_LABEL_MAX);
  demand(s.trim().length > 0 && s === s.trim());
  return s;
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
function validateFix(value: unknown): number {
  const f = record(value, ["at", "latitude", "longitude", "accuracyM"]);
  coordinate(f.latitude, 90);
  coordinate(f.longitude, 180);
  integer(f.accuracyM, 0, 100_000);
  return instant(f.at);
}
