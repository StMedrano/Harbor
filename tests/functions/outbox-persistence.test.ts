import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { createPersistentDispatchOne, type OutboxStore } from "../../supabase/functions/_shared/outbox.ts";

const route = { version: 1 as const, kind: "device.state.changed" };
const subscription = { endpoint: "https://push.example.test/1", p256dh: "public", auth: "secret" };

function store(transport: "fcm" | "web_push" = "web_push") {
  const changes: unknown[] = [];
  const target: Record<string, string> = {};
  if (transport === "fcm") target.deviceId = "device-1";
  else target.subscriptionId = "subscription-1";
  const persistence: OutboxStore = {
    async claim(id, now) { changes.push({ claim: id, now }); return [{ id, transport, target_ref: target, route_payload: route, attempt_count: 1 }]; },
    async complete(id) { changes.push({ complete: id }); return "sent"; },
    async fail(id, failure) { changes.push({ fail: id, ...failure }); return failure.retryable ? "retry" : "dead_letter"; },
    async readWebPush(id) { changes.push({ readWebPush: id }); return subscription; },
    async readFcmToken(id) { changes.push({ readFcmToken: id }); return "current-token"; },
    async disableWebPush(id) { changes.push({ disable: id }); },
  };
  return { persistence, changes };
}

Deno.test("persistent dispatcher resolves Web Push target privately and confirms completion", async () => {
  const h = store();
  let sent: unknown;
  const dispatch = createPersistentDispatchOne(h.persistence, {
    now: () => new Date("2026-10-04T00:00:00Z"),
    sendFcm: async () => { throw new Error("wrong transport"); },
    sendWebPush: async (target, payload) => { sent = { target, payload }; return { status: "sent" }; },
  });
  assertEquals(await dispatch("row-1"), { status: "sent" });
  assertEquals(sent, { target: subscription, payload: route });
  assertEquals(h.changes, [{ claim: "row-1", now: "2026-10-04T00:00:00.000Z" }, { readWebPush: "subscription-1" }, { complete: "row-1" }]);
});

Deno.test("persistent FCM dispatch resolves current private token rather than trusting queued tokens", async () => {
  const h = store("fcm");
  let sent: unknown;
  const dispatch = createPersistentDispatchOne(h.persistence, { sendFcm: async (token, payload) => { sent = { token, payload }; return { status: "sent" }; } });
  assertEquals(await dispatch("row-1"), { status: "sent" });
  assertEquals(sent, { token: "current-token", payload: route });
});

Deno.test("removed Web Push target is dead-lettered without provider delivery", async () => {
  const h = store();
  h.persistence.readWebPush = async () => null;
  let delivered = false;
  const dispatch = createPersistentDispatchOne(h.persistence, {
    sendFcm: async () => { throw new Error("wrong transport"); },
    sendWebPush: async () => { delivered = true; return { status: "sent" }; },
  });
  assertEquals(await dispatch("row-1"), { status: "dead_letter" });
  assertEquals(delivered, false);
});

Deno.test("persistent dispatcher does not report sent when SQL completion is unconfirmed", async () => {
  const h = store();
  h.persistence.complete = async () => "processing";
  await assertRejects(() => createPersistentDispatchOne(h.persistence, {
    sendFcm: async () => ({ status: "sent" }),
    sendWebPush: async () => ({ status: "sent" }),
  })("row-1"), Error, "Outbox completion was not confirmed");
});

Deno.test("persistent dispatcher preserves empty claim as no-op without resolving targets", async () => {
  const h = store();
  h.persistence.claim = async () => [];
  assertEquals(await createPersistentDispatchOne(h.persistence, { sendFcm: async () => { throw new Error("must not send"); } })("row-1"), { status: "no_op" });
  assertEquals(h.changes, []);
});
