import { assertEquals } from "jsr:@std/assert@1";
import fixture from "../../apps/parent-android/app/src/test/resources/usage-report-v1.json" with {
  type: "json",
};
import {
  claimDeviceAtomic,
  claimDeviceRequestNonceAtomic,
  createFamilyAtomic,
  getPrivateSql,
  issueDevicePairingAtomic,
  loadDeviceSecurity,
  revokeDeviceAtomic,
} from "../../supabase/functions/_shared/clients.ts";
import { requireDeviceProof } from "../../supabase/functions/_shared/device-proof.ts";
import {
  sha256Hex,
  verifyP256Sha256,
} from "../../supabase/functions/_shared/crypto.ts";
import {
  createClearUsageHandler,
  createGetUsageHandler,
  createReportUsageHandler,
  createUsageCheckpointHandler,
  type UsageEndpointDependencies,
} from "../../supabase/functions/_shared/usage.ts";
import { createUsagePersistence } from "../../supabase/functions/_shared/usage-persistence.ts";
import type {
  UsageReadReplyV1,
  UsageReportV1,
} from "../../packages/contracts/src/v1/usage.ts";

// Composes the real handlers, real P-256 device proof, real nonce claim and real private SQL.
// Live Auth issuance, deployed Edge Functions and observed phone behaviour remain separate hosted gates.
const REPORT_TIME = Date.parse("2026-10-08T12:00:30Z");
const EPOCH = fixture.epochId;
const encode = (bytes: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)));

function report(sequence: number, overrides: Partial<UsageReportV1> = {}) {
  return {
    ...structuredClone(fixture),
    sequence,
    ...overrides,
  } as UsageReportV1;
}

Deno.test("signed child usage reaches only authorized parents and survives clear, replay, revoke and expiry", async () => {
  const sql = getPrivateSql();
  const persistence = createUsagePersistence(sql);
  const owner = crypto.randomUUID(),
    ownerSession = crypto.randomUUID(),
    outsider = crypto.randomUUID(),
    outsiderSession = crypto.randomUUID(),
    removable = crypto.randomUUID(),
    removableSession = crypto.randomUUID(),
    childAuth = crypto.randomUUID(),
    childId = crypto.randomUUID();
  const families: string[] = [];
  const keys = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const wall = new Date();
  const proofDeps = {
    now: () => wall,
    requireDeviceIdentity: async () => ({
      userId: childAuth,
      accessToken: "fixture-child-token",
    }),
    loadDeviceSecurity,
    sha256: sha256Hex,
    verifyP256Signature: verifyP256Sha256,
    claimNonceAtomic: claimDeviceRequestNonceAtomic,
  };
  type Parent = {
    userId: string;
    sessionId: string;
    accessToken: string;
    aal: "aal1";
    amr: [];
  };
  const parents = new Map<string, Parent>(
    ([[owner, ownerSession], [outsider, outsiderSession], [
      removable,
      removableSession,
    ]] as const).map((
      [userId, sessionId],
    ) => [userId, {
      userId,
      sessionId,
      accessToken: "fixture",
      aal: "aal1" as const,
      amr: [] as [],
    }]),
  );
  let acting: string = owner;
  const deps: UsageEndpointDependencies = {
    now: () => REPORT_TIME,
    requireProof: (request, operation) =>
      requireDeviceProof(request, operation, proofDeps),
    requireParent: async () => parents.get(acting)!,
    sha256: sha256Hex,
    ...persistence,
  };
  const report$ = createReportUsageHandler(deps);
  const clear$ = createClearUsageHandler(deps);
  const checkpoint$ = createUsageCheckpointHandler(deps);
  const read$ = createGetUsageHandler(deps);
  try {
    for (
      const [id, anonymous] of [
        [owner, false],
        [outsider, false],
        [removable, false],
        [childAuth, true],
      ] as const
    ) {
      await sql`insert into auth.users(id,is_anonymous) values (${id}::uuid,${anonymous})`;
    }
    for (const [user, session] of parents) {
      await sql`insert into auth.sessions(id,user_id,created_at,updated_at) values (${session.sessionId}::uuid,${user}::uuid,now(),now())`;
    }
    const family = await createFamilyAtomic({
      userId: owner,
      name: "Usage composition",
      idempotencyKey: "usage-a",
    });
    families.push(family.familyId);
    const other = await createFamilyAtomic({
      userId: outsider,
      name: "Usage foreign",
      idempotencyKey: "usage-b",
    });
    families.push(other.familyId);
    await sql`insert into public.family_members(family_id,user_id,role,status) values (${family.familyId}::uuid,${removable}::uuid,'parent','active')`;
    await sql`insert into public.children(id,family_id,display_name) values (${childId}::uuid,${family.familyId}::uuid,'Usage child')`;
    const digest = await sha256Hex(`usage-pepper:${crypto.randomUUID()}`);
    await issueDevicePairingAtomic({
      parentUserId: owner,
      childId,
      codeDigest: digest,
      expiresAt: new Date(wall.getTime() + 600000).toISOString(),
    });
    const device = await claimDeviceAtomic({
      authUserId: childAuth,
      codeDigest: digest,
      publicKeySpki: encode(await crypto.subtle.exportKey("spki", keys.publicKey)),
      displayName: "Usage device",
      model: null,
      androidVersion: null,
      supervisionMode: "full",
    });
    const seconds = Math.floor(wall.getTime() / 1000);

    async function signed(
      operation: string,
      body: unknown,
      nonce: string = crypto.randomUUID(),
    ) {
      const raw = JSON.stringify(body);
      const canonical = [
        "POST",
        operation,
        device.deviceId,
        await sha256Hex(raw),
        String(seconds),
        nonce,
      ].join("\n");
      const signature = encode(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          keys.privateKey,
          new TextEncoder().encode(canonical),
        ),
      );
      return () =>
        new Request(`https://harbor.test/functions/v1/${operation}`, {
          method: "POST",
          body: raw,
          headers: {
            "X-Harbor-Device-Id": device.deviceId,
            "X-Harbor-Timestamp": String(seconds),
            "X-Harbor-Nonce": nonce,
            "X-Harbor-Signature": signature,
          },
        });
    }
    const upload = async (body: UsageReportV1) =>
      report$((await signed("report-device-usage", body))());
    const read = async (as: string): Promise<Response> => {
      acting = as;
      return read$(
        new Request("https://harbor.test/functions/v1/get-device-usage", {
          method: "POST",
          body: JSON.stringify({ deviceId: device.deviceId }),
        }),
      );
    };
    const readOk = async (as: string) => {
      const response = await read(as);
      assertEquals(response.status, 200);
      return await response.json() as UsageReadReplyV1;
    };

    // 1. Nothing uploaded yet: unknown, never a measured zero.
    assertEquals(await readOk(owner), {
      state: "none",
      report: null,
      receivedAt: null,
    });

    // 2. A signed upload is confirmed by the server and read back unchanged by the owning parent.
    const first = report(1);
    const sent = await upload(first);
    assertEquals(sent.status, 200);
    const receipt = await sent.json();
    assertEquals([receipt.confirmed, receipt.sequence], [true, 1]);
    const seen = await readOk(owner);
    assertEquals(seen.state, "available");
    assertEquals(seen.report, first);
    assertEquals(seen.receivedAt, receipt.receivedAt);
    assertEquals((await readOk(removable)).state, "available");

    // 3. A captured request cannot be replayed, and an identical retry with a fresh nonce is an exact no-op.
    const nonce = crypto.randomUUID();
    const once = await signed("report-device-usage", report(2), nonce);
    assertEquals((await report$(once())).status, 200);
    const replayed = await report$(once());
    assertEquals(replayed.status, 409);
    assertEquals((await replayed.json()).code, "REPLAY_REJECTED");
    const retry = await upload(report(2));
    assertEquals(retry.status, 200);
    assertEquals((await retry.json()).sequence, 2);
    const changed = report(2);
    changed.inventory[0].label = "Different payload, same sequence";
    const conflicting = await upload(changed);
    assertEquals(conflicting.status, 409);
    assertEquals((await conflicting.json()).code, "IDEMPOTENCY_CONFLICT");

    // 4. Foreign family and anonymous callers are denied; membership loss takes effect immediately.
    assertEquals((await read(outsider)).status, 403);
    await sql`update public.family_members set status='removed' where family_id=${family.familyId}::uuid and user_id=${removable}::uuid`;
    assertEquals((await read(removable)).status, 403);
    assertEquals((await readOk(owner)).state, "available");

    // 5. Clear, delayed replay of the older report, then a deliberate newer report in the same epoch.
    const cleared = await clear$(
      (await signed("clear-device-usage", {
        version: 1,
        epochId: EPOCH,
        sequence: 3,
      }))(),
    );
    assertEquals(cleared.status, 200);
    assertEquals((await readOk(owner)).report, null);
    assertEquals(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${device.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    const delayed = await upload(report(2));
    assertEquals(delayed.status, 409);
    assertEquals((await delayed.json()).code, "STALE_VERSION");
    assertEquals((await readOk(owner)).report, null);
    const checkpoint = await checkpoint$(
      (await signed("get-device-usage-checkpoint", { version: 1 }))(),
    );
    assertEquals(await checkpoint.json(), { sequence: 3, epochId: EPOCH });

    // 6. Offline catch-up is latest-only: skipped sequences are never required and the newest wins.
    assertEquals((await upload(report(9))).status, 200);
    assertEquals((await readOk(owner)).report?.sequence, 9);

    // 7. A denied permission report keeps unknown totals as null; the server never turns them into zero.
    const denied = report(10, {
      usagePermission: "denied",
      inventoryStatus: "unavailable",
      inventory: [],
      days: [{
        localDate: "2026-10-08",
        startAt: "2026-10-08T00:00:00.000Z",
        endAt: "2026-10-09T00:00:00.000Z",
        observedThrough: "2026-10-08T12:00:00.000Z",
        coverageStart: null,
        quality: "unavailable",
        totalMs: null,
        apps: [],
      }],
    });
    assertEquals((await upload(denied)).status, 200);
    const unknown = (await readOk(owner)).report!;
    assertEquals(unknown.usagePermission, "denied");
    assertEquals(unknown.days[0].totalMs, null);
    assertEquals(unknown.days[0].apps, []);

    // 8. Retention: an expired payload reads as expired; the purge removes it but keeps the sequence.
    assertEquals((await upload(report(11))).status, 200);
    await sql`update private.device_usage_snapshots set expires_at=clock_timestamp()-interval '1 second' where device_id=${device.deviceId}::uuid`;
    const expired = await readOk(owner);
    assertEquals([expired.state, expired.report], ["expired", null]);
    await sql`select private.harbor_purge_device_usage()`;
    assertEquals(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${device.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    await sql`update private.device_usage_checkpoints set received_at=clock_timestamp()-interval '31 days' where device_id=${device.deviceId}::uuid`;
    assertEquals((await readOk(owner)).state, "expired");
    assertEquals(
      (await (await checkpoint$(
        (await signed("get-device-usage-checkpoint", { version: 1 }))(),
      )).json()).sequence,
      11,
    );

    // 9. Revocation removes the payload and denies both sides at once; stored checkpoints stay private.
    assertEquals(
      (await upload(report(12))).status,
      200,
    );
    await revokeDeviceAtomic({
      deviceId: device.deviceId,
      familyId: family.familyId,
      actorUserId: owner,
    });
    assertEquals(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${device.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    const afterRevoke = await upload(report(13));
    assertEquals(afterRevoke.status, 403);
    assertEquals((await afterRevoke.json()).code, "DEVICE_REVOKED");
    const parentAfterRevoke = await read(owner);
    assertEquals(parentAfterRevoke.status, 403);
    assertEquals((await parentAfterRevoke.json()).code, "DEVICE_REVOKED");
  } finally {
    for (const id of families) {
      await sql`delete from private.notification_outbox where route_payload->>'familyId' = ${id}`;
      await sql`delete from private.audit_events where family_id = ${id}::uuid`;
      await sql`delete from public.families where id = ${id}::uuid`;
    }
    await sql`delete from auth.sessions where user_id in (${owner}::uuid,${outsider}::uuid,${removable}::uuid)`;
    await sql`delete from auth.users where id in (${owner}::uuid,${outsider}::uuid,${removable}::uuid,${childAuth}::uuid)`;
    assertEquals(
      (await sql`select count(*)::int n from private.device_usage_checkpoints c join public.devices_public d on d.id=c.device_id where d.family_id = any(${families}::uuid[])`)[
        0
      ].n,
      0,
    );
    await sql.end();
  }
});
