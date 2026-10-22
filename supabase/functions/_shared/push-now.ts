import { getPrivateSql } from "./clients.ts";
import { createPersistentDispatchOne, privateOutboxStore } from "./outbox.ts";
import { sendFcm } from "./fcm.ts";
import { sendParentFcm } from "./parent-fcm-delivery.ts";

type Handler = (request: Request) => Response | Promise<Response>;
type EdgeRuntimeLike = { waitUntil?(promise: Promise<unknown>): void };

/**
 * Sends the pushes that are due now: the ones a person needs to see or a phone needs to act on, queued in the last
 * 15 minutes (see private.harbor_due_notifications). Stale and superseded ones are retired first so they are never sent
 * late. Delivery failures are recorded on the outbox row for a later try; this never throws.
 */
export async function deliverDuePushes(limit = 20): Promise<void> {
  try {
    const sql = getPrivateSql();
    await sql`select private.harbor_retire_notifications()`;
    const rows = await sql<Array<{ outbox_id: string }>>`select outbox_id from private.harbor_due_notifications(${limit}::integer)`;
    if (rows.length === 0) return;
    const dispatchOne = createPersistentDispatchOne(privateOutboxStore, { sendFcm, sendParentFcm });
    for (const row of rows) {
      try {
        await dispatchOne(row.outbox_id);
      } catch {
        // Left pending; the next request that delivers pushes tries again.
      }
    }
  } catch {
    // Delivery never fails the request it follows.
  }
}

/**
 * Wraps a handler so that, after a successful answer, due pushes are delivered in the background. The answer is not
 * held back: the edge runtime keeps the function alive until delivery finishes.
 */
export function withPushDelivery(handler: Handler, deliver: () => Promise<void> = deliverDuePushes): Handler {
  return async (request) => {
    const response = await handler(request);
    if (response.status >= 200 && response.status < 300) {
      const delivery = deliver();
      const runtime = (globalThis as { EdgeRuntime?: EdgeRuntimeLike }).EdgeRuntime;
      if (runtime?.waitUntil) runtime.waitUntil(delivery);
      else await delivery;
    }
    return response;
  };
}
