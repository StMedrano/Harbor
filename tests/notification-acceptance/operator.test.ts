import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  checkpointEvent,
  cleanupStages,
  dispatchCheckpointed,
  dispatchRows,
  type FixtureManifest,
  type PreparationJournal,
  prepareFixture,
  readiness,
  validateDispatchRows,
  validateManifest,
} from "../hosted/notification-acceptance.ts";
const manifest: FixtureManifest = {
  runId: "11111111-1111-4111-8111-111111111111",
  projectRef: "bfvybxkjxilntjgndsrm",
  parentUserId: "22222222-2222-4222-8222-222222222222",
  familyId: "33333333-3333-4333-8333-333333333333",
  childId: "44444444-4444-4444-8444-444444444444",
  deviceId: "55555555-5555-4555-8555-555555555555",
  expectedDesiredStateVersion: 2,
  subscriptionIds: ["66666666-6666-4666-8666-666666666666"],
};
const route = {
  version: 1,
  kind: "device.state.changed",
  familyId: manifest.familyId,
  childId: manifest.childId,
  deviceId: manifest.deviceId,
};
const rows = [{
  id: "77777777-7777-4777-8777-777777777777",
  event_key: "desired-state:55555555-5555-4555-8555-555555555555:2",
  transport: "fcm",
  target_ref: { deviceId: manifest.deviceId },
  route_payload: route,
}, {
  id: "88888888-8888-4888-8888-888888888888",
  event_key: "desired-state:55555555-5555-4555-8555-555555555555:2",
  transport: "web_push",
  target_ref: { subscriptionId: manifest.subscriptionIds[0] },
  route_payload: route,
}];
Deno.test("UUID equivalence covers fixture routes targets and duplicate outbox IDs", () => {
  const m = {
    ...manifest,
    familyId: "abcdefab-abcd-4abc-8abc-abcdefabcdef",
    childId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    deviceId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    subscriptionIds: ["dddddddd-dddd-4ddd-8ddd-dddddddddddd"],
  };
  const r = {
    ...route,
    version: 1 as const,
    familyId: m.familyId.toUpperCase(),
    childId: m.childId.toUpperCase(),
    deviceId: m.deviceId.toUpperCase(),
    resourceId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  };
  const batch = rows.map((row, index) => ({
    ...row,
    id: index
      ? "aaaaaaab-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
      : "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    event_key: `desired-state:${m.deviceId}:2`,
    route_payload: r,
    target_ref: index
      ? { subscriptionId: m.subscriptionIds[0].toUpperCase() }
      : { deviceId: m.deviceId.toUpperCase() },
  }));
  const accepted = validateDispatchRows(m, batch);
  assertEquals(accepted.length, 2);
  assertEquals(accepted[0].route, r);
  assertThrows(() =>
    validateDispatchRows(m, [batch[0], {
      ...batch[1],
      id: batch[0].id.toUpperCase(),
    }])
  );
  assertThrows(() =>
    validateManifest({
      ...m,
      subscriptionIds: [
        m.subscriptionIds[0],
        m.subscriptionIds[0].toUpperCase(),
      ],
    })
  );
});
Deno.test("checkpointed dispatch preserves unrelated legacy history and refuses unsafe evidence before sending", async () => {
  const older = { ...manifest, expectedDesiredStateVersion: 1 };
  const history = checkpointEvent(
    older,
    validateDispatchRows(
      older,
      rows.map((row) => ({
        ...row,
        event_key: `desired-state:${manifest.deviceId}:1`,
        route_payload: {
          ...route,
          resourceId: "abcdefab-abcd-4abc-8abc-abcdefabcdef",
        },
      })),
    ),
    [],
  );
  let calls = 0, saves = 0;
  const send = async () => {
    calls++;
    return { status: "sent" as const };
  };
  const result = await dispatchCheckpointed(manifest, rows, "worker-input", {
    load: async () => history,
    save: async () => {
      saves++;
    },
    send,
  });
  assertEquals(calls, 2);
  assertEquals(saves, 0);
  assertEquals(result.eventMapping, null);
  assertEquals(result.outcomes.map((outcome) => outcome.status), [
    "sent",
    "sent",
  ]);
  const sameEvent = checkpointEvent(
    manifest,
    validateDispatchRows(
      manifest,
      rows.map((row) => ({
        ...row,
        route_payload: {
          ...route,
          resourceId: "abcdefab-abcd-4abc-8abc-abcdefabcdef",
        },
      })),
    ),
    [],
  );
  for (const input of [null, [{ ...history[0], recipients: [] }], sameEvent]) {
    calls = 0;
    await assertRejects(() =>
      dispatchCheckpointed(manifest, rows, "worker-input", {
        load: async () => input,
        save: async () => {},
        send,
      })
    );
    assertEquals(calls, 0);
  }
  calls = 0;
  await assertRejects(() =>
    dispatchCheckpointed(manifest, rows, "worker-input", {
      load: async () => {
        throw Error("unreadable history");
      },
      save: async () => {},
      send,
    })
  );
  assertEquals(calls, 0);
  const identified = rows.map((row) => ({
    ...row,
    route_payload: {
      ...route,
      resourceId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    },
  }));
  await assertRejects(() =>
    dispatchCheckpointed(manifest, identified, "worker-input", {
      load: async () => history,
      save: async () => {
        throw Error("checkpoint failed");
      },
      send,
    })
  );
  assertEquals(calls, 0);
  const sequence: string[] = [];
  const sent = await dispatchCheckpointed(
    manifest,
    identified,
    "worker-input",
    {
      load: async () => history,
      save: async (saved) => {
        sequence.push("save");
        assertEquals(saved.length, 2);
      },
      send: async () => {
        sequence.push("send");
        return { status: "sent" };
      },
    },
  );
  assertEquals(sequence, ["save", "send", "send"]);
  assertEquals(sent.eventMapping?.desiredStateVersion, 2);
});
Deno.test("identified dispatch shares one UUID and rejects mixed or malformed identity before sending", async () => {
  const resourceId = "abcdefab-abcd-4abc-8abc-abcdefabcdef";
  const identified = rows.map((row) => ({
    ...row,
    route_payload: { ...route, resourceId },
  }));
  assertEquals(validateDispatchRows(manifest, identified).length, 2);
  assertEquals(
    validateDispatchRows(manifest, [{
      ...identified[0],
      route_payload: { ...route, resourceId: resourceId.toUpperCase() },
    }, identified[1]]).length,
    2,
  );
  for (
    const bad of [
      [identified[0], rows[1]],
      [identified[0], {
        ...identified[1],
        route_payload: { ...route, resourceId: manifest.parentUserId },
      }],
      [identified[0], {
        ...identified[1],
        route_payload: { ...route, resourceId: "bad" },
      }],
      [{
        ...identified[0],
        route_payload: { ...route, resourceId, accessToken: "private" },
      }, identified[1]],
      [identified[0]],
    ]
  ) {
    let calls = 0;
    await assertRejects(() =>
      dispatchRows(manifest, bad, "worker-input", async () => {
        calls++;
        return { status: "sent" };
      })
    );
    assertEquals(calls, 0);
  }
});
Deno.test("event checkpoint is idempotent and refuses replaced recipients and UUID reuse", () => {
  const identified = validateDispatchRows(
    manifest,
    rows.map((row) => ({
      ...row,
      route_payload: {
        ...route,
        resourceId: "abcdefab-abcd-4abc-8abc-abcdefabcdef",
      },
    })),
  );
  const saved = checkpointEvent(manifest, identified, []);
  assertEquals(saved.length, 1);
  assertEquals(saved[0].desiredStateVersion, 2);
  assertEquals(
    checkpointEvent(manifest, identified.toReversed(), saved),
    saved,
  );
  assertEquals(
    checkpointEvent(
      manifest,
      identified.map((row) => ({
        ...row,
        route: {
          ...row.route,
          resourceId: row.route.resourceId!.toUpperCase(),
        },
      })),
      saved,
    ),
    saved,
  );
  assertThrows(() =>
    checkpointEvent(manifest, validateDispatchRows(manifest, rows), [])
  );
  assertThrows(() =>
    checkpointEvent(
      manifest,
      identified.map((row) => ({ ...row, id: manifest.parentUserId })),
      saved,
    )
  );
  assertThrows(() =>
    checkpointEvent(
      manifest,
      identified.map((row) => ({
        ...row,
        route: { ...row.route, resourceId: manifest.parentUserId },
      })),
      saved,
    )
  );
  const next = { ...manifest, expectedDesiredStateVersion: 3 };
  assertThrows(() =>
    checkpointEvent(
      next,
      identified.map((row) => ({
        ...row,
        eventKey: `desired-state:${manifest.deviceId}:3`,
      })),
      saved,
    )
  );
  for (
    const corrupt of [
      [{ ...saved[0], route: { ...saved[0].route, accessToken: "private" } }],
      [{ ...saved[0], eventKey: `desired-state:${manifest.deviceId}:1` }],
      [{ ...saved[0], recipients: [] }],
      [{ ...saved[0], unexpected: "private" }],
    ]
  ) assertThrows(() => checkpointEvent(manifest, identified, corrupt));
});
Deno.test("operator rejects wrong project before any dispatch", async () => {
  let calls = 0;
  await assertRejects(() =>
    dispatchRows(
      { ...manifest, projectRef: "foreign" },
      rows,
      "worker-input",
      async () => {
        calls++;
        return { status: "sent" };
      },
    )
  );
  assertEquals(calls, 0);
  assertThrows(() =>
    validateManifest({ ...manifest, deviceId: "not-a-fixture-uuid" })
  );
});
Deno.test("preparation protects handoff before API calls and rejects foreign project", async () => {
  let calls = 0;
  const deps = {
    protect: async () => {
      throw Error("ACL failed");
    },
    parent: async () => {
      calls++;
      return manifest.parentUserId;
    },
    family: async () => manifest.familyId,
    child: async () => manifest.childId,
    pairing: async () => {},
    checkpoint: async () => {},
    rollback: async () => {},
  };
  await assertRejects(() => prepareFixture(projectRef(), manifest.runId, deps));
  assertEquals(calls, 0);
  await assertRejects(() =>
    prepareFixture("foreign", manifest.runId, {
      ...deps,
      protect: async () => {
        calls++;
      },
    })
  );
  assertEquals(calls, 0);
});
function projectRef() {
  return manifest.projectRef;
}
Deno.test("partial preparation checkpoints and rolls back only allocated fixture IDs", async () => {
  let saved: PreparationJournal | undefined,
    rolled: PreparationJournal | undefined;
  await assertRejects(() =>
    prepareFixture(manifest.projectRef, manifest.runId, {
      protect: async () => {},
      parent: async () => manifest.parentUserId,
      family: async () => manifest.familyId,
      child: async () => {
        throw Error("private failure");
      },
      pairing: async () => {},
      checkpoint: async (journal) => {
        saved = { ...journal };
      },
      rollback: async (journal) => {
        rolled = { ...journal };
      },
    })
  );
  assertEquals(saved?.familyId, manifest.familyId);
  assertEquals(rolled, saved);
  assertEquals(rolled?.childId, undefined);
});
Deno.test("dispatch validation admits only exact fixture event, routes and recipient targets", () => {
  assertEquals(validateDispatchRows(manifest, rows).map((row) => row.id), [
    rows[0].id,
    rows[1].id,
  ]);
  for (
    const altered of [
      [{
        ...rows[0],
        route_payload: { ...route, familyId: manifest.parentUserId },
      }, rows[1]],
      [rows[0], {
        ...rows[1],
        target_ref: { subscriptionId: manifest.parentUserId },
      }],
      [{
        ...rows[0],
        event_key: "desired-state:55555555-5555-4555-8555-555555555555:1",
      }, rows[1]],
      [{ ...rows[0], target_ref: { deviceId: manifest.childId } }, rows[1]],
      [{
        ...rows[0],
        route_payload: { ...route, resourceId: manifest.parentUserId },
      }, rows[1]],
      [rows[0], { ...rows[1], id: rows[0].id }],
      [rows[0], {
        ...rows[1],
        target_ref: {
          subscriptionId: manifest.subscriptionIds[0],
          endpoint: "private",
        },
      }],
      [],
      [rows[0]],
    ]
  ) assertThrows(() => validateDispatchRows(manifest, altered));
});
Deno.test("dispatch without browser subscriptions still requires exactly one fixture FCM row", () => {
  assertEquals(
    validateDispatchRows({ ...manifest, subscriptionIds: [] }, [rows[0]])
      .length,
    1,
  );
  assertThrows(() =>
    validateDispatchRows({ ...manifest, subscriptionIds: [] }, rows)
  );
});
Deno.test("missing worker input performs no provider dispatch", async () => {
  let calls = 0;
  await assertRejects(() =>
    dispatchRows(manifest, rows, "", async () => {
      calls++;
      return { status: "sent" };
    })
  );
  assertEquals(calls, 0);
});
Deno.test("operator readiness never includes credentials or private errors", () => {
  const output = JSON.stringify(
    readiness({
      publishableKey: "public-input",
      serviceRoleKey: "server-private",
      workerKey: "worker-private",
      password: "parent-private",
    }),
  );
  for (
    const credential of [
      "public-input",
      "server-private",
      "worker-private",
      "parent-private",
    ]
  ) assertEquals(output.includes(credential), false);
});
Deno.test("partial cleanup attempts every stage and reports remaining fixture IDs", async () => {
  const attempted: string[] = [];
  const result = await cleanupStages(manifest, [
    {
      name: "browser",
      run: async () => {
        attempted.push("browser");
        throw Error("private error");
      },
    },
    {
      name: "revoke",
      run: async () => {
        attempted.push("revoke");
      },
    },
    {
      name: "outbox",
      run: async () => {
        attempted.push("outbox");
        throw Error("other private error");
      },
    },
    {
      name: "auth",
      run: async () => {
        attempted.push("auth");
      },
    },
  ]);
  assertEquals(attempted, ["browser", "revoke", "outbox", "auth"]);
  assertEquals(result.complete, false);
  assertEquals(result.failed, ["browser", "outbox"]);
  assertEquals(result.remainingFixtureIds.includes(manifest.familyId), true);
  assertEquals(JSON.stringify(result).includes("private error"), false);
});
