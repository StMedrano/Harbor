import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import { parsePublicConfig } from "../../tools/notification-acceptance/web/config.ts";
import {
  enablePush,
  installationStore,
  type PushLifecycleDependencies,
  removePush,
} from "../../tools/notification-acceptance/web/lifecycle.ts";

const publicConfig = {
  supabaseUrl: "https://bfvybxkjxilntjgndsrm.supabase.co",
  publishableKey: "sb_publishable_fixture",
  vapidPublicKey: "B" + "A".repeat(86),
};
function fixture() {
  const values = new Map<string, string>();
  const store = installationStore({
    getItem: (k: string) => values.get(k) ?? null,
    setItem: (k: string, v: string) => {
      values.set(k, v);
    },
  });
  let subscription:
    | { endpoint: string; keys: { p256dh: string; auth: string } }
    | null = null;
  const registered: unknown[] = [];
  let unsubscribed = 0;
  const deps: PushLifecycleDependencies = {
    requireSession: async () => {},
    installation: store,
    requestPermission: async () => "granted",
    getSubscription: async () => subscription,
    subscribe: async () =>
      subscription = {
        endpoint: "https://push.example.test/a",
        keys: { p256dh: "public", auth: "opaque" },
      },
    register: async (id, sub) => {
      registered.push({ id, ...sub });
    },
    remove: async () => {},
    unsubscribe: async () => {
      unsubscribed++;
      subscription = null;
    },
  };
  return { deps, values, registered, unsubscribed: () => unsubscribed };
}
Deno.test("public config refuses a foreign project and server credentials", () => {
  assertEquals(
    parsePublicConfig(publicConfig).supabaseUrl,
    publicConfig.supabaseUrl,
  );
  assertThrows(() =>
    parsePublicConfig({
      ...publicConfig,
      supabaseUrl: "https://foreign.supabase.co",
    })
  );
  for (
    const publishableKey of [
      "sb_secret_fixture",
      "eyJhbGciOiJIUzI1NiJ9.private.signature",
      "",
    ]
  ) assertThrows(() => parsePublicConfig({ ...publicConfig, publishableKey }));
});
Deno.test("permission refusal never registers a recipient", async () => {
  const f = fixture();
  f.deps.requestPermission = async () => "denied";
  await assertRejects(() => enablePush(f.deps));
  assertEquals(f.registered, []);
});
Deno.test("retry reuses subscription and installation identity", async () => {
  const f = fixture();
  const first = await enablePush(f.deps);
  f.deps.subscribe = async () => {
    throw Error("Must reuse subscription");
  };
  assertEquals(await enablePush(f.deps), first);
  assertEquals(f.registered.length, 2);
});
Deno.test("backend rejection never reports successful registration", async () => {
  const f = fixture();
  f.deps.register = async () => {
    throw Error("Backend refused");
  };
  await assertRejects(() => enablePush(f.deps), Error, "Backend refused");
  assertEquals(f.registered, []);
});
Deno.test("failed backend removal keeps local subscription for retry", async () => {
  const f = fixture();
  await enablePush(f.deps);
  f.deps.remove = async () => {
    throw Error("Retry removal");
  };
  await assertRejects(() => removePush(f.deps));
  assertEquals(f.unsubscribed(), 0);
  f.deps.remove = async () => {};
  await removePush(f.deps);
  assertEquals(f.unsubscribed(), 1);
});
Deno.test("reload retains only installation identity and requires a fresh session", async () => {
  const f = fixture();
  const first = await enablePush(f.deps);
  assertEquals([...f.values.keys()], ["harbor.acceptance.installationId"]);
  assertEquals([...f.values.values()], [first.installationId]);
  f.deps.requireSession = async () => {
    throw Error("Sign in again");
  };
  await assertRejects(() => enablePush(f.deps), Error, "Sign in again");
  assertEquals(f.registered.length, 1);
});
