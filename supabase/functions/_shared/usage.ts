import {
  parseUsageReport,
  validateClearUsage,
  validateUsageCheckpointRequest,
} from "../../../packages/contracts/src/v1/usage.ts";
import { databaseError, HarborAuthError } from "./errors.ts";
import { jsonError } from "./responses.ts";
import type {
  ClearUsageV1,
  UsageCheckpointReplyV1,
  UsageReadReplyV1,
  UsageReportV1,
  UsageWriteReplyV1,
} from "../../../packages/contracts/src/v1/usage.ts";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
export type UsageEndpointDependencies = {
  now(): number;
  requireProof(req: Request, operation: string): Promise<DeviceProofContext>;
  requireParent(req: Request): Promise<ParentContext>;
  sha256(body: string): Promise<string>;
  writeUsage(
    ctx: DeviceProofContext,
    report: UsageReportV1,
    hash: string,
  ): Promise<UsageWriteReplyV1>;
  clearUsage(
    ctx: DeviceProofContext,
    clear: ClearUsageV1,
    hash: string,
  ): Promise<UsageWriteReplyV1>;
  readUsage(parent: ParentContext, deviceId: string): Promise<UsageReadReplyV1>;
  readUsageCheckpoint(ctx: DeviceProofContext): Promise<UsageCheckpointReplyV1>;
};
const MAX_BYTES = 1_048_576;
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
async function boundedRequest(
  req: Request,
): Promise<{ request: Request; raw: string }> {
  const declared = req.headers.get("content-length");
  if (declared !== null && Number(declared) > MAX_BYTES) {
    throw new HarborAuthError(
      "VALIDATION_FAILED",
      413,
      "Usage report is too large",
    );
  }
  const reader = req.body?.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  if (reader) {
    try {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > MAX_BYTES) {
          await reader.cancel().catch(() => {});
          throw new HarborAuthError(
            "VALIDATION_FAILED",
            413,
            "Usage report is too large",
          );
        }
        chunks.push(next.value);
      }
    } finally {
      reader.releaseLock();
    }
  }
  const bytes = new Uint8Array(size);
  let cursor = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, cursor);
    cursor += chunk.byteLength;
  }
  const raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  return {
    raw,
    request: new Request(req.url, {
      method: req.method,
      headers: req.headers,
      body: raw,
    }),
  };
}
function handler(
  action: (req: Request) => Promise<unknown>,
): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method !== "POST") {
      return jsonError("VALIDATION_FAILED", 405, "Use POST");
    }
    try {
      return Response.json(await action(req), {
        headers: { "cache-control": "no-store" },
      });
    } catch (error) {
      const known = databaseError(error) ?? error;
      if (known instanceof HarborAuthError) {
        return jsonError(known.code, known.status, known.message);
      }
      if (
        known instanceof TypeError || known instanceof SyntaxError ||
        known instanceof RangeError
      ) {
        return jsonError(
          "VALIDATION_FAILED",
          400,
          "Usage request input is invalid",
        );
      }
      return Response.json({ message: "Internal server error" }, {
        status: 500,
      });
    }
  };
}
export function createReportUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "report-device-usage");
    const report = parseUsageReport(raw, deps.now());
    return deps.writeUsage(ctx, report, await deps.sha256(raw));
  });
}
export function createClearUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "clear-device-usage");
    const clear = validateClearUsage(JSON.parse(raw));
    return deps.clearUsage(ctx, clear, await deps.sha256(raw));
  });
}
export function createUsageCheckpointHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const ctx = await deps.requireProof(request, "get-device-usage-checkpoint");
    validateUsageCheckpointRequest(JSON.parse(raw));
    return deps.readUsageCheckpoint(ctx);
  });
}
export function createGetUsageHandler(deps: UsageEndpointDependencies) {
  return handler(async (req) => {
    const { request, raw } = await boundedRequest(req);
    const parent = await deps.requireParent(request);
    if (!parent.sessionId || !uuid.test(parent.sessionId)) {
      throw new HarborAuthError(
        "AUTH_REQUIRED",
        401,
        "Current parent session is required",
      );
    }
    const value = JSON.parse(raw);
    if (
      !value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length !== 1 || typeof value.deviceId !== "string" ||
      !uuid.test(value.deviceId)
    ) throw new TypeError("Invalid usage target");
    return deps.readUsage(parent, value.deviceId);
  });
}
