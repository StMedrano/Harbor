import { assertEquals } from "jsr:@std/assert@1";
import { withCors } from "../../supabase/functions/_shared/http.ts";
Deno.test("browser preflight bypasses auth and allowed responses include CORS", async () => {
  let calls = 0;
  const handler = withCors(async () => { calls++; return Response.json({ ok: true }); }, () => ["https://parent.test"]);
  const preflight = await handler(new Request("https://api.test", { method: "OPTIONS", headers: { Origin: "https://parent.test", "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "authorization,apikey,content-type" } }));
  assertEquals(preflight.status, 204); assertEquals(calls, 0);
  assertEquals(preflight.headers.get("access-control-allow-origin"), "https://parent.test");
  const success = await handler(new Request("https://api.test", { method: "POST", headers: { Origin: "https://parent.test" } }));
  assertEquals(success.headers.get("access-control-allow-origin"), "https://parent.test");
  assertEquals((await handler(new Request("https://api.test", { method: "POST", headers: { Origin: "https://unapproved.test" } }))).status, 403);
  assertEquals(calls, 1);
});
Deno.test("SQL domain failures return stable sanitized HTTP errors with CORS", async () => {
  for (const [code, message, expected, status] of [["P0001","STALE_VERSION","STALE_VERSION",409],["42501","DEVICE_REVOKED","DEVICE_REVOKED",403],["42501","private SQL details","FORBIDDEN",403],["22P02","invalid uuid private SQL details","VALIDATION_FAILED",400]]) {
    const handler = withCors(async () => { throw { code, message }; }, () => ["https://parent.test"]);
    const response = await handler(new Request("https://api.test", { method: "POST", headers: { Origin: "https://parent.test" } }));
    assertEquals(response.status, status);
    assertEquals((await response.json()).code, expected);
    assertEquals(response.headers.get("access-control-allow-origin"), "https://parent.test");
  }
});
