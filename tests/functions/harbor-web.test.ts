import { handleHarborWeb, targetFunction, webOriginAllowed } from "../../supabase/functions/harbor-web/index.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}
function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
}

const BASE = "https://bfvybxkjxilntjgndsrm.supabase.co";
const ORIGIN = "https://harbor-git-feat-activity-app-control-stalinvmedrano-1274.vercel.app";
const request = (name: string, init: RequestInit & { origin?: string | null } = {}) =>
  new Request(`${BASE}/functions/v1/harbor-web/${name}`, {
    method: init.method ?? "POST",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer caller-token",
      ...(init.origin === null ? {} : { origin: init.origin ?? ORIGIN }),
      ...(init.headers as Record<string, string> ?? {}),
    },
    ...(init.method && init.method !== "POST" ? {} : { body: (init.body as string) ?? "{}" }),
  });

function recorder(reply = new Response('{"ok":true}', { status: 200, headers: { "content-type": "application/json" } })) {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  return { calls, send: async (url: string, init: RequestInit) => { calls.push({ url, init }); return reply; } };
}

Deno.test("harbor-web forwards a listed function with the caller's own token and no browser origin", async () => {
  const r = recorder();
  const response = await handleHarborWeb(request("get-device-controls", { body: '{"version":1}' }), r.send, BASE);
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("access-control-allow-origin"), ORIGIN);
  assertEquals(r.calls.length, 1);
  assertEquals(r.calls[0].url, `${BASE}/functions/v1/get-device-controls`);
  const sent = r.calls[0].init.headers as Headers;
  assertEquals(sent.get("authorization"), "Bearer caller-token");
  assert(!sent.get("origin"), "the forwarded request must carry no browser origin");
  assert(!sent.get("cookie"));
});

Deno.test("harbor-web reaches only the functions it lists, only by POST", async () => {
  const r = recorder();
  for (const name of ["dispatch-outbox", "report-device-activity", "", "../create-family"]) {
    assertEquals((await handleHarborWeb(request(name), r.send, BASE)).status, 404, name);
  }
  assertEquals((await handleHarborWeb(request("create-family", { method: "GET" }), r.send, BASE)).status, 404);
  assertEquals(r.calls.length, 0);
  assertEquals(targetFunction(`${BASE}/functions/v1/harbor-web/create-family/`), "create-family");
  assertEquals(targetFunction(`${BASE}/functions/v1/harbor-web/report-message-alert`), null);
});

Deno.test("harbor-web answers only the web app's own origins", async () => {
  const r = recorder();
  for (const origin of [ORIGIN, "https://harbor-lyart-nu.vercel.app", "https://harbor-lyart-nu-stalinvmedrano-1274.vercel.app", "http://localhost:5191", "http://127.0.0.1:5173"]) {
    assert(webOriginAllowed(origin), origin);
  }
  for (const origin of ["https://harbor.example.invalid", "https://evil-stalinvmedrano-1274.vercel.app.attacker.test", "http://localhost", null]) {
    assert(!webOriginAllowed(origin), String(origin));
  }
  const refused = await handleHarborWeb(request("create-family", { origin: "https://harbor.example.invalid" }), r.send, BASE);
  assertEquals(refused.status, 403);
  assertEquals(refused.headers.get("access-control-allow-origin"), null);
  assertEquals(r.calls.length, 0);
});

Deno.test("harbor-web answers the browser's preflight and refuses an oversized body", async () => {
  const r = recorder();
  const preflight = await handleHarborWeb(request("create-family", { method: "OPTIONS" }), r.send, BASE);
  assertEquals(preflight.status, 204);
  assertEquals(preflight.headers.get("access-control-allow-origin"), ORIGIN);
  assert(preflight.headers.get("access-control-allow-headers")?.includes("x-harbor-signature"));
  const big = await handleHarborWeb(request("device-sync", { body: "x".repeat(65_537) }), r.send, BASE);
  assertEquals(big.status, 413);
  assertEquals(r.calls.length, 0);
});

Deno.test("harbor-web passes a refusal straight back and says when Harbor cannot be reached", async () => {
  const denied = recorder(new Response('{"code":"AUTH_REQUIRED","message":"Authentication is required"}', { status: 401, headers: { "content-type": "application/json" } }));
  const response = await handleHarborWeb(request("create-family"), denied.send, BASE);
  assertEquals(response.status, 401);
  assertEquals((await response.json()).code, "AUTH_REQUIRED");
  const offline = await handleHarborWeb(request("create-family"), () => Promise.reject(new Error("network")), BASE);
  assertEquals(offline.status, 502);
});
