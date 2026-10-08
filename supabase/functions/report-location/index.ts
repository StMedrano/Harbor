import { serveHarbor } from "../_shared/http.ts";
import { recordLocationsAtomic, type RecordLocationsResult } from "../_shared/clients.ts";
import {
  defaultDeviceProofDependencies,
  requireDeviceProof,
  type DeviceProofContext,
} from "../_shared/device-proof.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export const MAX_LOCATION_POINTS = 50;
const MAX_BODY_BYTES = 32 * 1024;

export type ReportLocationDeps = {
  requireDeviceProof(req: Request): Promise<DeviceProofContext>;
  recordLocations(input: { deviceId: string; points: unknown[] }): Promise<RecordLocationsResult>;
};

function invalid(): Response {
  return jsonError("VALIDATION_FAILED", 400, "Location input is invalid");
}

// Device proof is verified first, so unauthenticated callers learn nothing
// about the shape the endpoint accepts. Per-point validation (ranges, clock
// skew, retention) happens atomically in the database.
export function createReportLocationHandler(deps: ReportLocationDeps) {
  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return jsonError("VALIDATION_FAILED", 405, "Method not allowed");
    const ctx = await deps.requireDeviceProof(req);

    const text = await req.text();
    if (text.length > MAX_BODY_BYTES) return invalid();
    let body: unknown;
    try { body = JSON.parse(text); } catch { return invalid(); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return invalid();
    const points = (body as Record<string, unknown>).points;
    if (!Array.isArray(points) || points.length < 1 || points.length > MAX_LOCATION_POINTS) return invalid();

    const result = await deps.recordLocations({ deviceId: ctx.deviceId, points });
    return Response.json(result, { status: 200 });
  };
}

export const defaultReportLocationDeps: ReportLocationDeps = {
  requireDeviceProof: (request) => requireDeviceProof(request, "report-location", defaultDeviceProofDependencies),
  recordLocations: recordLocationsAtomic,
};

if (import.meta.main) {
  const handler = createReportLocationHandler(defaultReportLocationDeps);
  serveHarbor(async (request) => {
    try {
      return await handler(request);
    } catch (error) {
      const known = databaseError(error);
      if (known) return jsonError(known.code, known.status, known.message);
      if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
      console.error("report-location failed");
      return Response.json({ message: "Internal server error" }, { status: 500 });
    }
  });
}
