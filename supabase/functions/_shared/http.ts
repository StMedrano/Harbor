import { databaseError } from "./errors.ts";
import { jsonError } from "./responses.ts";

type Handler = (request: Request) => Response | Promise<Response>;
const allowedHeaders = "authorization, apikey, content-type, x-client-info, x-supabase-api-version, x-harbor-device-id, x-harbor-timestamp, x-harbor-nonce, x-harbor-signature";
export function withCors(handler: Handler, origins: () => string[] = () => (Deno.env.get("HARBOR_ALLOWED_ORIGINS") ?? "").split(",").map((origin) => origin.trim()).filter(Boolean)): Handler {
  return async (request) => {
    const origin = request.headers.get("origin");
    if (origin && !origins().includes(origin)) return jsonError("FORBIDDEN", 403, "Origin is not allowed");
    const headers = new Headers({ Vary: "Origin" });
    if (origin) headers.set("access-control-allow-origin", origin);
    let response: Response;
    if (request.method === "OPTIONS") {
      headers.set("access-control-allow-methods", "POST, OPTIONS");
      headers.set("access-control-allow-headers", allowedHeaders);
      const requestedMethod = request.headers.get("access-control-request-method");
      const requestedHeaders = (request.headers.get("access-control-request-headers") ?? "").toLowerCase().split(",").map((header) => header.trim()).filter(Boolean);
      const permitted = (!requestedMethod || requestedMethod === "POST") && requestedHeaders.every((header) => allowedHeaders.split(", ").includes(header));
      return new Response(null, { status: permitted ? 204 : 403, headers });
    }
    try { response = await handler(request); }
    catch (error) {
      const domain = databaseError(error);
      response = domain ? jsonError(domain.code, domain.status, domain.message) : Response.json({ message: "Internal server error" }, { status: 500 });
    }
    const resultHeaders = new Headers(response.headers);
    for (const [name, value] of headers) {
      if (name === "vary" && resultHeaders.has(name)) resultHeaders.append(name, value);
      else resultHeaders.set(name, value);
    }
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers: resultHeaders });
  };
}
export function serveHarbor(handler: Handler) { return Deno.serve(withCors(handler)); }
