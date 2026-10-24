/**
 * The Harbor Family web app's way in. The browser calls /functions/v1/harbor-web/<name>; this forwards the request to
 * that function on this same project, server side, with the caller's own token and Harbor's request headers.
 *
 * Why it exists: every other function answers only the web origins its deployment was configured with, and this
 * branch's preview deployments get a new host name each time. This one function names the web app's origins instead,
 * so no other function has to change. It forwards from the server, so the function it calls sees no browser origin.
 *
 * It holds no key and makes no decision of its own: only the functions listed here can be reached, only by POST, and
 * each of them checks the caller's token exactly as it does for the Android app. That is why this one does not check
 * the token itself (verify_jwt is off): a request with no token or a bad one is refused by the function it reaches.
 */
const FUNCTIONS = new Set([
  "create-family", "create-child", "create-device-pairing", "create-parent-invite", "accept-parent-invite", "revoke-device",
  "get-device-usage", "get-device-location", "get-message-alerts", "get-device-activity", "get-device-checkins", "request-device-checkin",
  "get-device-controls", "update-device-state", "get-access-requests", "decide-access-request",
  "device-claim", "device-sync",
]);
const PASS = ["authorization", "apikey", "content-type", "x-harbor-device-id", "x-harbor-timestamp", "x-harbor-nonce", "x-harbor-signature"];
const ALLOW_HEADERS = PASS.join(", ");
const MAX_BYTES = 65_536;

/** This project's own preview deployments of the web app, and a developer's local copy of it. */
export function webOriginAllowed(origin: string | null): boolean {
  if (!origin) return false;
  return /^https:\/\/harbor-[a-z0-9-]*stalinvmedrano-1274\.vercel\.app$/.test(origin) ||
    /^http:\/\/localhost:\d{2,5}$/.test(origin) ||
    /^http:\/\/127\.0\.0\.1:\d{2,5}$/.test(origin);
}

/**
 * The function a request names, or null when it is not one this may reach. The name has to be the whole of what
 * follows this function's own path, so a path that walks out of it (`harbor-web/../x`, which the URL flattens away)
 * names nothing.
 */
export function targetFunction(url: string): string | null {
  const path = new URL(url).pathname.replace(/\/+$/, "");
  const here = path.indexOf("/harbor-web/");
  if (here < 0) return null;
  const name = path.slice(here + "/harbor-web/".length);
  return FUNCTIONS.has(name) ? name : null;
}

function refuse(status: number, code: string, message: string, origin: string | null): Response {
  return new Response(JSON.stringify({ code, message }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...(origin ? { "access-control-allow-origin": origin } : {}), vary: "Origin" },
  });
}

export async function handleHarborWeb(request: Request, send: (url: string, init: RequestInit) => Promise<Response>, base: string): Promise<Response> {
  const origin = request.headers.get("origin");
  const allowed = webOriginAllowed(origin);
  if (origin && !allowed) return refuse(403, "FORBIDDEN", "Origin is not allowed", null);
  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        ...(allowed && origin ? { "access-control-allow-origin": origin } : {}),
        "access-control-allow-methods": "POST, OPTIONS",
        "access-control-allow-headers": ALLOW_HEADERS,
        "access-control-max-age": "600",
        vary: "Origin",
      },
    });
  }
  const name = targetFunction(request.url);
  if (request.method !== "POST" || !name) return refuse(404, "VALIDATION_FAILED", "Not found", allowed ? origin : null);
  const body = await request.arrayBuffer();
  if (body.byteLength > MAX_BYTES) return refuse(413, "VALIDATION_FAILED", "Request is too large", allowed ? origin : null);
  const headers = new Headers();
  for (const header of PASS) {
    const value = request.headers.get(header);
    if (value) headers.set(header, value);
  }
  let reply: Response;
  try {
    reply = await send(`${base}/functions/v1/${name}`, { method: "POST", headers, body });
  } catch {
    return refuse(502, "DEVICE_OFFLINE", "Harbor could not be reached", allowed ? origin : null);
  }
  return new Response(reply.body, {
    status: reply.status,
    headers: {
      "content-type": reply.headers.get("content-type") ?? "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...(allowed && origin ? { "access-control-allow-origin": origin } : {}),
      vary: "Origin",
    },
  });
}

if (import.meta.main) {
  const base = Deno.env.get("SUPABASE_URL")?.replace(/\/+$/, "") ?? "";
  Deno.serve((request) => handleHarborWeb(request, (url, init) => fetch(url, init), base));
}
