import { databaseError, HarborAuthError } from "./errors.ts";
import { jsonError } from "./responses.ts";

/** Reads a request body with a hard byte cap and returns both the raw text and a replayable Request. */
export async function boundedRequest(
  req: Request,
  maxBytes: number,
  tooLarge: string,
): Promise<{ request: Request; raw: string }> {
  const declared = req.headers.get("content-length");
  if (declared !== null && Number(declared) > maxBytes) {
    throw new HarborAuthError("VALIDATION_FAILED", 413, tooLarge);
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
        if (size > maxBytes) {
          await reader.cancel().catch(() => {});
          throw new HarborAuthError("VALIDATION_FAILED", 413, tooLarge);
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

/** POST-only JSON handler with the shared error mapping. */
export function postHandler(
  action: (req: Request) => Promise<unknown>,
  invalidInput: string,
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
        return jsonError("VALIDATION_FAILED", 400, invalidInput);
      }
      return Response.json({ message: "Internal server error" }, {
        status: 500,
      });
    }
  };
}
