import { assertEquals } from "jsr:@std/assert@1";
import {
  notificationClick,
  parseReceipt,
  receivePush,
} from "../../tools/notification-acceptance/web/receipts.ts";
const now = "2026-10-04T22:00:00.000Z";
const route = {
  version: 1 as const,
  kind: "device.desired_state.changed",
  deviceId: "12345678-1234-4234-8234-123456789abc",
};
Deno.test("receipt rejects content, credentials and malformed routes", () => {
  for (
    const value of [
      { ...route, accessToken: "secret" },
      { ...route, location: "private" },
      { ...route, message: "private" },
      { ...route, version: 2 },
      { ...route, deviceId: "bad" },
      "{",
      null,
    ]
  ) assertEquals(parseReceipt(value, now), null);
  assertEquals(parseReceipt(route, now), { route, receivedAt: now });
  assertEquals(parseReceipt(JSON.stringify(route), now), {
    route,
    receivedAt: now,
  });
});
Deno.test("duplicate notification hints only record local receipts", async () => {
  const receipts: unknown[] = [];
  let shown = 0;
  const deps = {
    save: async (r: unknown) => {
      receipts.push(r);
    },
    show: async () => {
      shown++;
    },
  };
  await receivePush(JSON.stringify(route), now, deps);
  await receivePush(JSON.stringify(route), now, deps);
  assertEquals(receipts, [{ route, receivedAt: now }, {
    route,
    receivedAt: now,
  }]);
  assertEquals(shown, 2);
  await receivePush(
    JSON.stringify({ ...route, message: "private" }),
    now,
    deps,
  );
  assertEquals(receipts.length, 2);
  assertEquals(shown, 2);
});
Deno.test("notification click ignores untrusted destinations", async () => {
  const opened: string[] = [];
  await notificationClick(
    { url: "https://evil.example/" },
    "http://localhost:3000",
    async (url: string) => {
      opened.push(url);
    },
  );
  assertEquals(opened, ["http://localhost:3000/"]);
});

