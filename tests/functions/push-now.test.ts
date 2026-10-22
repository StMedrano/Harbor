import { strict as assert } from "node:assert";
import { withPushDelivery } from "../../supabase/functions/_shared/push-now.ts";

Deno.test("pushes are delivered after a successful answer, never after a failed one", async () => {
  let delivered = 0;
  const deliver = async () => { delivered++; };
  const ok = withPushDelivery(() => Response.json({ ok: true }), deliver);
  assert.equal((await ok(new Request("https://harbor.test", { method: "POST" }))).status, 200);
  assert.equal(delivered, 1);
  const bad = withPushDelivery(() => new Response(null, { status: 403 }), deliver);
  assert.equal((await bad(new Request("https://harbor.test", { method: "POST" }))).status, 403);
  assert.equal(delivered, 1);
});

Deno.test("the answer is not held back by delivery when the edge runtime can finish it later", async () => {
  const waited: Promise<unknown>[] = [];
  (globalThis as { EdgeRuntime?: unknown }).EdgeRuntime = { waitUntil: (p: Promise<unknown>) => { waited.push(p); } };
  try {
    let release!: () => void;
    const slow = () => new Promise<void>((r) => { release = r; });
    const response = await withPushDelivery(() => Response.json({}), slow)(new Request("https://harbor.test", { method: "POST" }));
    assert.equal(response.status, 200);
    assert.equal(waited.length, 1);
    release();
    await waited[0];
  } finally {
    delete (globalThis as { EdgeRuntime?: unknown }).EdgeRuntime;
  }
});
