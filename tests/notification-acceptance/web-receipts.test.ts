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
Deno.test("state event identity survives serialized receipt persistence and duplicates", async () => {
  const identified = {
    ...route,
    kind: "device.state.changed",
    familyId: "11111111-1111-4111-8111-111111111111",
    childId: "22222222-2222-4222-8222-222222222222",
    resourceId: "33333333-3333-4333-8333-333333333333",
  };
  const saved: string[] = [];
  const deps = {
    save: async (value: unknown) => {
      saved.push(JSON.stringify(value));
    },
    show: async () => {},
  };
  await receivePush(identified, now, deps);
  await receivePush(identified, now, deps);
  const read = saved.map((text) => {
    const value = JSON.parse(text);
    return parseReceipt(value.route, value.receivedAt);
  });
  assertEquals(read, [{ route: identified, receivedAt: now }, {
    route: identified,
    receivedAt: now,
  }]);
  assertEquals(parseReceipt({ ...identified, resourceId: "bad" }, now), null);
  assertEquals(
    parseReceipt({ ...identified, accessToken: "private" }, now),
    null,
  );
  assertEquals(parseReceipt(route, now), { route, receivedAt: now });
});
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
