import { assertEquals } from "jsr:@std/assert@1";
import { assetResponse } from "../../tools/notification-acceptance/web/serve.ts";
Deno.test("static recipient exposes only built assets and refuses other origins", async () => {
  const read = async (name: string) => new TextEncoder().encode(name);
  assertEquals(
    await (await assetResponse(new Request("http://localhost:3000/"), read))
      .text(),
    "index.html",
  );
  for (
    const path of [
      "/.env",
      "/deno.lock",
      "/handoff.json",
      "/app.js/map",
      "/%2e%2e%2f.env",
    ]
  ) {
    assertEquals(
      (await assetResponse(new Request("http://localhost:3000" + path), read))
        .status,
      404,
    );
  }
  assertEquals(
    (await assetResponse(new Request("http://foreign.test:3000/app.js"), read))
      .status,
    403,
  );
  assertEquals(
    (await assetResponse(
      new Request("http://localhost:3000/app.js", { method: "POST" }),
      read,
    )).status,
    405,
  );
});
