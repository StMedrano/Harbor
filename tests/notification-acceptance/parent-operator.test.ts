import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  correlateParentReceipts,
  dispatchParentRows,
  parentJournal,
  validateParentDispatchRows,
  validateParentManifest,
} from "../hosted/parent-android-acceptance.ts";
import {
  classifyDelivery,
  projectRef,
} from "../hosted/notification-acceptance.ts";
const id = (n: number) =>
  `${String(n).padStart(8, "0")}-1111-4111-8111-111111111111`;
const manifest = {
  runId: id(1),
  projectRef,
  parentUserId: id(2),
  familyId: id(3),
  childId: id(4),
  deviceId: id(5),
  childAuthUserId: id(6),
  expectedDesiredStateVersion: 1,
  subscriptionIds: [id(7)],
  parentSessionId: id(8),
  parentRegistrationId: id(9),
  parentInstallationId: id(10),
};
const route = {
  version: 1 as const,
  kind: "device.state.changed",
  familyId: manifest.familyId,
  childId: manifest.childId,
  deviceId: manifest.deviceId,
  resourceId: id(11),
};
const eventKey = `desired-state:${manifest.deviceId}:1`;
const rows = [
  {
    id: id(12),
    event_key: eventKey,
    transport: "fcm",
    target_ref: { deviceId: manifest.deviceId },
    route_payload: route,
  },
  {
    id: id(13),
    event_key: eventKey,
    transport: "web_push",
    target_ref: { subscriptionId: id(7) },
    route_payload: route,
  },
  {
    id: id(14),
    event_key: eventKey,
    transport: "fcm",
    target_ref: {
      parentFcmRegistrationId: id(9),
      userId: manifest.parentUserId,
    },
    route_payload: route,
  },
];
Deno.test("parent operator requires exact three-recipient binding and strips credentials from journal", () => {
  assertEquals(validateParentDispatchRows(manifest, rows).length, 3);
  assertEquals(
    "workerKey" in
      validateParentManifest({ ...manifest, workerKey: "synthetic-secret" }),
    false,
  );
  assertThrows(() =>
    validateParentManifest({ ...manifest, projectRef: "other" })
  );
  assertThrows(() => validateParentDispatchRows(manifest, rows.slice(0, 2)));
  assertThrows(() => validateParentDispatchRows(manifest, [...rows, rows[2]]));
  assertThrows(() =>
    validateParentDispatchRows(manifest, [...rows.slice(0, 2), {
      ...rows[2],
      target_ref: { ...rows[2].target_ref, userId: id(15) },
    }])
  );
});
Deno.test("parent operator checkpoints all recipients before dispatch and never persists worker key", async () => {
  let saved = false;
  let calls = 0;
  await assertRejects(() =>
    dispatchParentRows(manifest, rows, "synthetic-worker", {
      checkpoint: () => Promise.reject(Error("storage denied")),
      send: () => {
        calls++;
        return Promise.resolve({ status: "sent" as const });
      },
    })
  );
  assertEquals(calls, 0);
  const result = await dispatchParentRows(manifest, rows, "synthetic-worker", {
    checkpoint: (mapping) => {
      assertEquals(JSON.stringify(mapping).includes("synthetic-worker"), false);
      saved = true;
      return Promise.resolve();
    },
    send: (_id, key) => {
      assertEquals(saved, true);
      assertEquals(key, "synthetic-worker");
      calls++;
      return Promise.resolve({ status: "sent" as const });
    },
  });
  assertEquals(calls, 3);
  assertEquals(result.outcomes.map((o) => o.status), ["sent", "sent", "sent"]);
});
Deno.test("provider acceptance alone is not parent receipt and old binding or event is refused", () => {
  const started = "2026-01-01T00:00:00Z";
  const mapping = {
    eventKey,
    desiredStateVersion: 1,
    route,
    recipients: validateParentDispatchRows(manifest, rows).map((
      { id, transport, targetRef },
    ) => ({ id, transport, targetRef })),
  };
  const sample = {
    registrationId: manifest.parentRegistrationId,
    route,
    receivedAt: Date.parse(started) + 1000,
  };
  assertEquals(classifyDelivery({ status: "sent" }, [], 1), "unverified");
  const accepted = correlateParentReceipts(
    manifest,
    eventKey,
    started,
    [sample],
    0,
    mapping,
  );
  assertEquals(accepted.length, 1);
  assertEquals(classifyDelivery({ status: "sent" }, accepted, 1), "received");
  for (
    const invalid of [
      { ...sample, registrationId: id(15) },
      { ...sample, route: { ...route, resourceId: id(16) } },
      { ...sample, token: "synthetic-sensitive" },
      { ...sample, receivedAt: Date.parse(started) - 1 },
    ]
  ) {
    assertEquals(
      correlateParentReceipts(
        manifest,
        eventKey,
        started,
        [invalid],
        0,
        mapping,
      ),
      [],
    );
  }
});

Deno.test("protected parent journal retains exact mapping but never accepts credential fields", () => {
  const mapping = {
    eventKey,
    desiredStateVersion: 1,
    route,
    recipients: validateParentDispatchRows(manifest, rows).map((
      { id, transport, targetRef },
    ) => ({ id, transport, targetRef })),
  };
  const journal = parentJournal(
    { ...manifest, workerKey: "synthetic-secret" },
    { ...mapping, accessToken: "synthetic-secret" },
  );
  assertEquals(JSON.stringify(journal).includes("synthetic-secret"), false);
  assertEquals(journal.mapping.recipients.length, 3);
  assertThrows(() =>
    parentJournal(manifest, { ...mapping, desiredStateVersion: 2 })
  );
});
