import { timingSafeEqual } from "node:crypto";
import type { DeliveryOutcome } from "../_shared/outbox-dispatch.ts";
export { createDispatchOne, type DeliveryOutcome, type OutboxFailure, type OutboxNotification, type DispatchDependencies } from "../_shared/outbox-dispatch.ts";

export function createOutboxWorkerHandler(
  dispatchOne: (id: string) => Promise<DeliveryOutcome>,
  readWorkerKey: () => string | undefined,
) {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });
    const expected = readWorkerKey();
    if (!expected?.trim()) return new Response(null, { status: 503 });
    const actual = request.headers.get("apikey");
    if (!actual || actual.length > 1024) return new Response(null, { status: 403 });
    const digest = async (value: string) => new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
    if (!timingSafeEqual(await digest(actual), await digest(expected))) return new Response(null, { status: 403 });
    const body = await request.json().catch(() => null);
    if (!body || typeof body.outboxId !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.outboxId)) return new Response(null, { status: 400 });
    try {
      return Response.json(await dispatchOne(body.outboxId));
    } catch {
      return new Response(null, { status: 500 });
    }
  };
}

if (import.meta.main) {
  const { createPersistentDispatchOne, privateOutboxStore } = await import("../_shared/outbox.ts");
  const { sendFcm } = await import("../_shared/fcm.ts");
  const handler = createOutboxWorkerHandler(createPersistentDispatchOne(privateOutboxStore, { sendFcm }), () => Deno.env.get("HARBOR_OUTBOX_WORKER_KEY"));
  Deno.serve(handler);
}
