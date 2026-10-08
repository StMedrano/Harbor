import { assertEquals } from "jsr:@std/assert@1";
import { requireParent } from "../../supabase/functions/_shared/auth.ts";
import { requireRecentAal2 } from "../../supabase/functions/_shared/aal.ts";

Deno.test("stateless default Auth retains the caller's verified recent MFA claims", async () => {
  const names = ["SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY"];
  const previous = names.map((name) => Deno.env.get(name));
  const originalFetch = globalThis.fetch;
  const epoch = Math.floor(Date.now() / 1000);
  const encode = (value: unknown) => btoa(JSON.stringify(value)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
  const token = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: "parent-a", exp: epoch + 3600, iat: epoch, aal: "aal2", amr: [{ method: "totp", timestamp: epoch }], role: "authenticated" })}.c2lnbmF0dXJl`;
  try {
    Deno.env.set(names[0], "https://acceptance.supabase.test");
    Deno.env.set(names[1], "fixture-publishable-key");
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      assertEquals(request.url, "https://acceptance.supabase.test/auth/v1/user");
      assertEquals(request.headers.get("authorization"), `Bearer ${token}`);
      return new Response(JSON.stringify({ id: "parent-a", is_anonymous: false, app_metadata: {}, user_metadata: {} }), { headers: { "content-type": "application/json" } });
    };
    const context = await requireParent(new Request("https://harbor.test", { headers: { Authorization: `Bearer ${token}` } }));
    assertEquals(context.aal, "aal2");
    assertEquals(context.amr, [{ method: "totp", timestamp: epoch }]);
    requireRecentAal2(context, epoch);
  } finally {
    globalThis.fetch = originalFetch;
    names.forEach((name, index) => previous[index] === undefined ? Deno.env.delete(name) : Deno.env.set(name, previous[index]!));
  }
});
