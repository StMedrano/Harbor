import { strict as assert } from "node:assert";

import {
  ContractVersion,
  HarborErrorCodes,
  type NotificationRouteRefV1,
} from "../src/index.ts";

Deno.test("ContractVersion is pinned to V1", () => {
  assert.equal(ContractVersion, 1);
});

Deno.test("HarborErrorCodes exposes the exact stable V1 literals", () => {
  assert.deepEqual(HarborErrorCodes, [
    "AUTH_REQUIRED",
    "MFA_REQUIRED",
    "FORBIDDEN",
    "VALIDATION_FAILED",
    "DEVICE_REVOKED",
    "DEVICE_OFFLINE",
    "STALE_VERSION",
    "REPLAY_REJECTED",
    "IDEMPOTENCY_CONFLICT",
  ]);
});

Deno.test("notification route refs contain identifiers only", () => {
  const routeRef: NotificationRouteRefV1 = {
    version: 1,
    kind: "device.offline",
    familyId: "family-123",
    childId: "child-123",
    deviceId: "device-123",
    resourceId: "alert-123",
  };

  assert.deepEqual(Object.keys(routeRef).sort(), [
    "childId",
    "deviceId",
    "familyId",
    "kind",
    "resourceId",
    "version",
  ]);

  const forbiddenSensitiveFields = [
    "latitude",
    "longitude",
    "location",
    "message",
    "messageBody",
    "content",
    "body",
  ];

  for (const field of forbiddenSensitiveFields) {
    assert.equal(
      Object.hasOwn(routeRef, field),
      false,
      `route reference must not expose ${field}`,
    );
  }
});
