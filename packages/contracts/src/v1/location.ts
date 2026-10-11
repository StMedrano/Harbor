export type LocationStatusV1 =
  | "sharing"
  | "permission_lost"
  | "background_missing"
  | "services_off";
export type LocationProviderV1 = "fused" | "gps" | "network";
export type LocationFixV1 = {
  at: string;
  latitude: number;
  longitude: number;
  accuracyM: number;
  provider: LocationProviderV1;
};
export type LocationReportV1 = {
  version: 1;
  epochId: string;
  sequence: number;
  observedAt: string;
  status: LocationStatusV1;
  fixes: LocationFixV1[];
};
export type ClearLocationV1 = { version: 1; epochId: string; sequence: number };
export type LocationWriteReplyV1 = {
  confirmed: true;
  sequence: number;
  receivedAt: string;
};
export type LocationReadReplyV1 = {
  state: "available" | "none" | "expired";
  status: LocationStatusV1 | null;
  observedAt: string | null;
  receivedAt: string | null;
  fixes: LocationFixV1[];
};
export type LocationCheckpointRequestV1 = { version: 1 };
export type LocationCheckpointReplyV1 = {
  sequence: number;
  epochId: string | null;
};

export const LOCATION_MAX_BYTES = 262_144;
export const LOCATION_MAX_FIXES = 100;
export const LOCATION_RETENTION_MS = 30 * 86_400_000;

export function validateLocationReport(
  value: unknown,
  nowMs: number,
): LocationReportV1 {
  demand(
    new TextEncoder().encode(JSON.stringify(value)).length <=
      LOCATION_MAX_BYTES,
  );
  return validateReport(value, nowMs);
}
export function parseLocationReport(
  raw: string,
  nowMs: number,
): LocationReportV1 {
  demand(new TextEncoder().encode(raw).length <= LOCATION_MAX_BYTES);
  return validateLocationReport(JSON.parse(raw), nowMs);
}
export function validateClearLocation(value: unknown): ClearLocationV1 {
  const r = record(value, ["version", "epochId", "sequence"]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  return structuredClone(value) as ClearLocationV1;
}
export function validateLocationCheckpointRequest(
  value: unknown,
): LocationCheckpointRequestV1 {
  const r = record(value, ["version"]);
  demand(r.version === 1);
  return { version: 1 };
}
export function validateLocationCheckpointReply(
  value: unknown,
): LocationCheckpointReplyV1 {
  const r = record(value, ["sequence", "epochId"]);
  const n = integer(r.sequence);
  if (n === 0) demand(r.epochId === null);
  else uuid(r.epochId);
  return structuredClone(value) as LocationCheckpointReplyV1;
}

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
function demand(ok: boolean): asserts ok {
  if (!ok) throw new TypeError("Invalid location reporting contract");
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
/** A finite coordinate in [-limit, limit] with at most six decimal places (about 0.1 m). */
function coordinate(value: unknown, limit: number): number {
  demand(
    typeof value === "number" && Number.isFinite(value) &&
      Math.abs(value) <= limit,
  );
  demand(Number(value.toFixed(6)) === value);
  return value;
}
function validateFix(value: unknown): number {
  const f = record(value, [
    "at",
    "latitude",
    "longitude",
    "accuracyM",
    "provider",
  ]);
  coordinate(f.latitude, 90);
  coordinate(f.longitude, 180);
  integer(f.accuracyM, 0, 100_000);
  demand(["fused", "gps", "network"].includes(String(f.provider)));
  return instant(f.at);
}
function validateReport(value: unknown, nowMs: number): LocationReportV1 {
  demand(Number.isFinite(nowMs));
  const r = record(value, [
    "version",
    "epochId",
    "sequence",
    "observedAt",
    "status",
    "fixes",
  ]);
  demand(r.version === 1);
  uuid(r.epochId);
  integer(r.sequence, 1);
  const observed = instant(r.observedAt);
  demand(observed <= nowMs + 300_000);
  demand(
    ["sharing", "permission_lost", "background_missing", "services_off"]
      .includes(String(r.status)),
  );
  demand(Array.isArray(r.fixes) && r.fixes.length <= LOCATION_MAX_FIXES);
  let previous = -Infinity;
  for (const fix of r.fixes) {
    const at = validateFix(fix);
    demand(at <= observed && at >= observed - LOCATION_RETENTION_MS);
    demand(at > previous);
    previous = at;
  }
  return structuredClone(value) as LocationReportV1;
}
