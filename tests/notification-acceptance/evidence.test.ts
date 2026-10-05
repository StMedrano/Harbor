import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  classifyDelivery,
  correlateReceipts,
  type FixtureManifest,
} from "../hosted/notification-acceptance.ts";
const manifest: FixtureManifest = {
  runId: "11111111-1111-4111-8111-111111111111",
  projectRef: "bfvybxkjxilntjgndsrm",
  parentUserId: "22222222-2222-4222-8222-222222222222",
  familyId: "33333333-3333-4333-8333-333333333333",
  childId: "44444444-4444-4444-8444-444444444444",
  deviceId: "55555555-5555-4555-8555-555555555555",
  expectedDesiredStateVersion: 1,
  subscriptionIds: [],
};
const eventKey = `desired-state:${manifest.deviceId}:1`,
  start = "2026-10-04T12:00:00.000Z";
const receipt = {
  route: {
    version: 1 as const,
    kind: "device.state.changed",
    familyId: manifest.familyId,
    childId: manifest.childId,
    deviceId: manifest.deviceId,
  },
  receivedAt: "2026-10-04T12:00:01.000Z",
};
Deno.test("provider acceptance without receipt stays unverified at timeout", () => {
  assertEquals(classifyDelivery({ status: "sent" }, [], 1), "unverified");
  assertEquals(classifyDelivery({ status: "sent" }, [], 120), "unverified");
  assertEquals(
    classifyDelivery({ status: "retry" }, [], 120),
    "provider_failure",
  );
  assertEquals(
    classifyDelivery({ status: "dead_letter" }, [], 1),
    "provider_failure",
  );
});
Deno.test("foreign, old, late and baselined hints cannot satisfy current observation", () => {
  const input = [
    { ...receipt, receivedAt: "2026-10-04T11:59:59Z" },
    { ...receipt, route: { ...receipt.route, deviceId: manifest.childId } },
    { ...receipt, receivedAt: "2026-10-04T12:02:01Z" },
    receipt,
  ];
  assertEquals(correlateReceipts(manifest, eventKey, start, input, 4), []);
  assertEquals(correlateReceipts(manifest, eventKey, start, input, 0), [
    receipt,
  ]);
  assertThrows(() =>
    correlateReceipts(
      manifest,
      `desired-state:${manifest.deviceId}:0`,
      start,
      input,
      0,
    )
  );
});
Deno.test("valid receipt passes while duplicate hints represent one authoritative event", () => {
  const matched = correlateReceipts(manifest, eventKey, start, [
    receipt,
    receipt,
  ], 0);
  assertEquals(matched.length, 2);
  assertEquals(classifyDelivery({ status: "sent" }, matched, 2), "received");
  assertEquals(manifest.expectedDesiredStateVersion, 1);
});
Deno.test("normalization accepts Android epoch time and rejects sensitive evidence", () => {
  assertEquals(
    correlateReceipts(manifest, eventKey, start, [{
      ...receipt,
      receivedAt: Date.parse(receipt.receivedAt),
    }], 0),
    [receipt],
  );
  assertEquals(
    correlateReceipts(manifest, eventKey, start, [{
      ...receipt,
      route: { ...receipt.route, password: "private" },
    }], 0),
    [],
  );
});

Deno.test("no-op cannot establish provider acceptance from a route hint", () => {
  assertEquals(
    classifyDelivery({ status: "no_op" }, [receipt], 1),
    "unverified",
  );
});
Deno.test("delayed duplicate from an earlier version cannot prove the later version", () => {
  assertEquals(
    correlateReceipts(
      { ...manifest, expectedDesiredStateVersion: 2 },
      `desired-state:${manifest.deviceId}:2`,
      start,
      [receipt],
      0,
    ),
    [],
  );
});
