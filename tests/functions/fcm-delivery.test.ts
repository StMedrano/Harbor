import { assertEquals } from "jsr:@std/assert@1";
import { sendFcmWithDependencies } from "../../supabase/functions/_shared/fcm.ts";
const route = { version: 1 as const, kind: "device.state.changed" };

Deno.test("FCM HTTP v1 uses server OAuth and sends only route data", async () => {
  let actual: unknown;
  const result = await sendFcmWithDependencies("device-token", route, {
    projectId: "harbor-test",
    getAccessToken: async () => "server-oauth",
    async fetch(url, options) { actual = { url, headers: options.headers, body: JSON.parse(options.body as string) }; return new Response('{"name":"message-1"}', { status: 200 }); },
  });
  assertEquals(result, { status: "sent" });
  assertEquals(actual, { url: "https://fcm.googleapis.com/v1/projects/harbor-test/messages:send", headers: { Authorization: "Bearer server-oauth", "content-type": "application/json" }, body: { message: { token: "device-token", data: { route: JSON.stringify(route) }, android: { priority: "high" } } } });
});

Deno.test("FCM classifies provider, auth, rate-limit and network failures without provider content", async () => {
  for (const [status, expected] of [
    [404, { status: "permanent_failure", reason: "provider_rejected" }],
    [400, { status: "permanent_failure", reason: "provider_rejected" }],
    [401, { status: "retryable_failure", reason: "provider_error" }],
    [403, { status: "retryable_failure", reason: "provider_error" }],
    [429, { status: "retryable_failure", reason: "rate_limited" }],
    [503, { status: "retryable_failure", reason: "provider_error" }],
  ] as const) {
    assertEquals(await sendFcmWithDependencies("token", route, { projectId: "harbor-test", getAccessToken: async () => "oauth", fetch: async () => new Response("private provider content", { status }) }), expected);
  }
  assertEquals(await sendFcmWithDependencies("token", route, { projectId: "harbor-test", getAccessToken: async () => { throw new Error("credential failure"); }, fetch: async () => { throw new Error("must not send"); } }), { status: "retryable_failure", reason: "provider_error" });
  assertEquals(await sendFcmWithDependencies("token", route, { projectId: "harbor-test", getAccessToken: async () => "oauth", fetch: async () => { throw new Error("network private content"); } }), { status: "retryable_failure", reason: "network_error" });
});

Deno.test("FCM rejects sensitive payload fields before credentials or network use", async () => {
  assertEquals(await sendFcmWithDependencies("token", { ...route, message: "private" }, { projectId: "harbor-test", getAccessToken: async () => { throw new Error("must not authenticate"); }, fetch: async () => { throw new Error("must not send"); } }), { status: "permanent_failure", reason: "provider_rejected" });
});
