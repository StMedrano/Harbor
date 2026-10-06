import { assertEquals } from "jsr:@std/assert@1";
import {
  configuredOrigins,
  withCors,
} from "../../supabase/functions/_shared/http.ts";
const preview =
  "https://harbor-git-feat-notification-accepta-83c20a-stalinvmedrano-1274.vercel.app";
Deno.test("development preview origin is additive and keeps previous CORS recipients", async () => {
  const env = {
    HARBOR_ALLOWED_ORIGINS: "http://localhost:3000, https://existing.test",
    HARBOR_ACCEPTANCE_PREVIEW_ORIGIN: preview,
    SUPABASE_URL: "https://bfvybxkjxilntjgndsrm.supabase.co",
  };
  const origins = configuredOrigins((key) => env[key as keyof typeof env]);
  assertEquals(origins, [
    "http://localhost:3000",
    "https://existing.test",
    preview,
  ]);
  let calls = 0;
  const handler = withCors(() => {
    calls++;
    return new Response(null, { status: 401 });
  }, () => origins);
  const preflight = await handler(
    new Request("https://edge.test/", {
      method: "OPTIONS",
      headers: { Origin: preview, "access-control-request-method": "POST" },
    }),
  );
  assertEquals(preflight.status, 204);
  assertEquals(calls, 0);
  const actual = await handler(
    new Request("https://edge.test/", {
      method: "POST",
      headers: { Origin: preview },
    }),
  );
  assertEquals(actual.status, 401);
  assertEquals(actual.headers.get("access-control-allow-origin"), preview);
});
Deno.test("acceptance origin setting is ignored outside exact development backend or for another origin", () => {
  for (
    const [backend, origin] of [["https://production.supabase.co", preview], [
      "https://bfvybxkjxilntjgndsrm.supabase.co",
      "https://foreign.test",
    ], ["https://bfvybxkjxilntjgndsrm.supabase.co", "*"]]
  ) {
    const env = {
      HARBOR_ALLOWED_ORIGINS: "https://existing.test",
      HARBOR_ACCEPTANCE_PREVIEW_ORIGIN: origin,
      SUPABASE_URL: backend,
    };
    assertEquals(configuredOrigins((key) => env[key as keyof typeof env]), [
      "https://existing.test",
    ]);
  }
});
