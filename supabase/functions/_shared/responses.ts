import type { HarborErrorCode } from "../../../packages/contracts/src/v1/errors.ts";

export function jsonError(
  code: HarborErrorCode,
  status: number,
  message: string,
): Response {
  return new Response(JSON.stringify({ code, message }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
