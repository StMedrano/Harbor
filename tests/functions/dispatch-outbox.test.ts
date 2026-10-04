import { assertEquals } from "jsr:@std/assert@1";
import { createDispatchOne, type DispatchDependencies, type OutboxNotification } from "../../supabase/functions/dispatch-outbox/index.ts";

const route = { version: 1 as const, kind: "device.state.changed", deviceId: "11111111-1111-4111-8111-111111111111" };

function harness(payload: unknown = route) {
  const rows = new Map<string, OutboxNotification>([
    ["fcm", { id: "fcm", transport: "fcm", targetRef: { deviceId: route.deviceId }, routePayload: payload, attemptCount: 0 }],
    ["web", { id: "web", transport: "web_push", targetRef: { subscriptionId: "subscription-1" }, routePayload: payload, attemptCount: 0 }],
  ]);
  const states = new Map<string, string>([["fcm", "pending"], ["web", "pending"]]);
  const delivered: unknown[] = [];
  const failures: unknown[] = [];
  const disabled: string[] = [];
  const deps: DispatchDependencies = {
    now: () => new Date("2026-10-04T00:00:00Z"),
    async claim(id) {
      if (states.get(id) !== "pending") return null;
      states.set(id, "processing");
      const row = rows.get(id)!;
      return { ...row, attemptCount: row.attemptCount + 1 };
    },
    async complete(id) { states.set(id, "sent"); },
    async fail(id, failure) { failures.push({ id, ...failure }); states.set(id, failure.retryable ? "retry" : "dead_letter"); },
    async sendFcm(target, payload) { delivered.push({ target, payload }); return { status: "sent" }; },
    async sendWebPush(target, payload) { delivered.push({ target, payload }); return { status: "sent" }; },
    async disableWebPush(id) { disabled.push(id); },
  };
  return { deps, states, delivered, failures, disabled };
}

Deno.test("dispatcher sends independent transport rows and duplicate dispatch is a no-op", async () => {
  const h = harness();
  const dispatch = createDispatchOne(h.deps);
  assertEquals(await dispatch("fcm"), { status: "sent" });
  assertEquals(await dispatch("fcm"), { status: "no_op" });
  assertEquals(await dispatch("web"), { status: "sent" });
  assertEquals(h.delivered.length, 2);
  assertEquals(h.states.get("fcm"), "sent");
  assertEquals(h.states.get("web"), "sent");
  assertEquals((h.delivered[0] as { payload: unknown }).payload, route);
});

Deno.test("invalid Web Push disables its subscription without undoing successful FCM", async () => {
  const h = harness();
  h.deps.sendWebPush = async () => ({ status: "permanent_failure", reason: "invalid_subscription" });
  const dispatch = createDispatchOne(h.deps);
  await dispatch("fcm");
  assertEquals(await dispatch("web"), { status: "dead_letter" });
  assertEquals(h.disabled, ["subscription-1"]);
  assertEquals(h.states.get("fcm"), "sent");
  assertEquals(h.states.get("web"), "dead_letter");
});

Deno.test("transient delivery failure persists bounded retry scheduling", async () => {
  const h = harness();
  h.deps.sendWebPush = async () => ({ status: "retryable_failure", reason: "rate_limited" });
  assertEquals(await createDispatchOne(h.deps)("web"), { status: "retry" });
  assertEquals(h.failures, [{ id: "web", retryable: true, errorCategory: "rate_limited", nextAttemptAt: "2026-10-04T00:01:00.000Z" }]);
});

Deno.test("transport exception schedules retry without logging provider content", async () => {
  const h = harness();
  h.deps.sendFcm = async () => { throw new Error("sensitive provider response"); };
  assertEquals(await createDispatchOne(h.deps)("fcm"), { status: "retry" });
  assertEquals(h.failures, [{ id: "fcm", retryable: true, errorCategory: "network_error", nextAttemptAt: "2026-10-04T00:01:00.000Z" }]);
});

Deno.test("dispatcher rejects location and message content before either transport sends", async () => {
  for (const payload of [{ ...route, location: { latitude: 1 } }, { ...route, message: "private" }, { version: 2, kind: "changed" }]) {
    for (const transport of ["fcm", "web"]) {
      const h = harness(payload);
      assertEquals(await createDispatchOne(h.deps)(transport), { status: "dead_letter" });
      assertEquals(h.delivered, []);
      assertEquals(h.failures, [{ id: transport, retryable: false, errorCategory: "invalid_payload", nextAttemptAt: null }]);
    }
  }
});

Deno.test("dispatcher carries claim attempt into completion and invalid cleanup", async () => {
  const h = harness();
  let completion: unknown;
  let cleanup: unknown;
  h.deps.complete = async (...args) => { completion = args; };
  h.deps.disableWebPush = async (...args) => { cleanup = args; };
  const dispatch = createDispatchOne(h.deps);
  await dispatch("fcm");
  assertEquals(completion, ["fcm", 1]);
  h.deps.sendWebPush = async () => ({ status: "permanent_failure", reason: "invalid_subscription" });
  await dispatch("web");
  assertEquals(cleanup, ["subscription-1", "web", 1]);
});
