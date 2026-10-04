import { assertEquals } from "jsr:@std/assert@1";
import { createOutboxWorkerHandler } from "../../supabase/functions/dispatch-outbox/index.ts";
const id = "11111111-1111-4111-8111-111111111111";
function request(key?: string, body: unknown = { outboxId: id }) {
  return new Request("https://harbor.test/dispatch-outbox", { method: "POST", headers: key ? { apikey: key } : {}, body: JSON.stringify(body) });
}
Deno.test("worker denies missing, parent and wrong credentials before dispatch", async () => {
  let calls = 0;
  const handler = createOutboxWorkerHandler(async () => { calls++; return { status: "sent" }; }, () => "worker-secret");
  for (const key of [undefined, "parent-jwt", "wrong-secret"]) assertEquals((await handler(request(key))).status, 403);
  assertEquals(calls, 0);
});
Deno.test("worker fails closed without server secret and validates outbox id", async () => {
  const dispatch = async () => { throw new Error("must not dispatch"); };
  assertEquals((await createOutboxWorkerHandler(dispatch, () => undefined)(request("worker-secret"))).status, 503);
  assertEquals((await createOutboxWorkerHandler(dispatch, () => "worker-secret")(request("worker-secret", { outboxId: "bad-id" }))).status, 400);
});
Deno.test("authorized worker dispatches only referenced durable intent", async () => {
  let actual: unknown;
  const handler = createOutboxWorkerHandler(async (outboxId) => { actual = outboxId; return { status: "sent" }; }, () => "worker-secret");
  const response = await handler(request("worker-secret", { outboxId: id, payload: "ignored" }));
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { status: "sent" });
  assertEquals(actual, id);
});
