import { assertEquals } from "jsr:@std/assert@1";
import fixture from "../../apps/parent-android/app/src/test/resources/location-report-v1.json" with {
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
  createClearLocationHandler,
  createGetLocationHandler,
  createLocationCheckpointHandler,
  createReportLocationHandler,
  type LocationEndpointDependencies,
} from "../../supabase/functions/_shared/location.ts";
import { createLocationPersistence } from "../../supabase/functions/_shared/location-persistence.ts";
import type {
  LocationReadReplyV1,
  LocationReportV1,
} from "../../packages/contracts/src/v1/location.ts";

// Composes the real handlers, real P-256 device proof, real nonce claim and real private SQL.
// Live Auth issuance, deployed Edge Functions and observed phone behaviour remain separate hosted gates.
const REPORT_TIME = Date.now();
const EPOCH = fixture.epochId;
const encode = (bytes: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)));

function minutesAgo(m: number) {
  return new Date(REPORT_TIME - m * 60000).toISOString().replace(
    /\.\d{3}Z$/,
    "Z",
  );
}
function report(sequence: number, overrides: Partial<LocationReportV1> = {}) {
  return {
    ...structuredClone(fixture),
    sequence,
    observedAt: minutesAgo(0),
    fixes: [
      {
        at: minutesAgo(10),
        latitude: 41.878114,
        longitude: -87.629798,
        accuracyM: 18,
        provider: "fused",
      },
      {
        at: minutesAgo(5),
        latitude: 41.878301,
        longitude: -87.629512,
        accuracyM: 650,
        provider: "network",
      },
    ],
    ...overrides,
  } as LocationReportV1;
}

Deno.test("signed child location reaches only authorized parents and survives clear, replay, revoke and expiry", async () => {
  const sql = getPrivateSql();
  const persistence = createLocationPersistence(sql);
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
  const deps: LocationEndpointDependencies = {
    now: () => REPORT_TIME,
    requireProof: (request, operation) =>
      requireDeviceProof(request, operation, proofDeps),
    requireParent: async () => parents.get(acting)!,
    sha256: sha256Hex,
    ...persistence,
  };
  const report$ = createReportLocationHandler(deps);
  const clear$ = createClearLocationHandler(deps);
  const checkpoint$ = createLocationCheckpointHandler(deps);
  const read$ = createGetLocationHandler(deps);
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
      name: "Location composition",
      idempotencyKey: "location-a",
    });
    families.push(family.familyId);
    const other = await createFamilyAtomic({
      userId: outsider,
      name: "Location foreign",
      idempotencyKey: "location-b",
    });
    families.push(other.familyId);
    await sql`insert into public.family_members(family_id,user_id,role,status) values (${family.familyId}::uuid,${removable}::uuid,'parent','active')`;
    await sql`insert into public.children(id,family_id,display_name) values (${childId}::uuid,${family.familyId}::uuid,'Location child')`;
    const digest = await sha256Hex(`location-pepper:${crypto.randomUUID()}`);
    await issueDevicePairingAtomic({
      parentUserId: owner,
      childId,
      codeDigest: digest,
      expiresAt: new Date(wall.getTime() + 600000).toISOString(),
    });
    const device = await claimDeviceAtomic({
      authUserId: childAuth,
      codeDigest: digest,
      publicKeySpki: encode(
        await crypto.subtle.exportKey("spki", keys.publicKey),
      ),
      displayName: "Location device",
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
    const upload = async (body: LocationReportV1) =>
      report$((await signed("report-device-location", body))());
    const read = async (as: string): Promise<Response> => {
      acting = as;
      return read$(
        new Request("https://harbor.test/functions/v1/get-device-location", {
          method: "POST",
          body: JSON.stringify({ deviceId: device.deviceId }),
        }),
      );
    };
    const readOk = async (as: string) => {
      const response = await read(as);
      assertEquals(response.status, 200);
      return await response.json() as LocationReadReplyV1;
    };

    // 1. Nothing uploaded yet: unknown, never an invented position.
    assertEquals(await readOk(owner), {
      state: "none",
      status: null,
      observedAt: null,
      receivedAt: null,
      fixes: [],
    });

    // 2. A signed upload is confirmed by the server and read back, newest first, by the owning parent.
    const first = report(1);
    const sent = await upload(first);
    assertEquals(sent.status, 200);
    const receipt = await sent.json();
    assertEquals([receipt.confirmed, receipt.sequence], [true, 1]);
    const seen = await readOk(owner);
    assertEquals(seen.state, "available");
    assertEquals(seen.status, "sharing");
    assertEquals(seen.fixes, [...first.fixes].reverse());
    assertEquals(seen.receivedAt, receipt.receivedAt);
    assertEquals((await readOk(removable)).state, "available");

    // 3. A captured request cannot be replayed; an identical retry with a fresh nonce is an exact no-op; a changed payload conflicts.
    const nonce = crypto.randomUUID();
    const once = await signed("report-device-location", report(2), nonce);
    assertEquals((await report$(once())).status, 200);
    const replayed = await report$(once());
    assertEquals(replayed.status, 409);
    assertEquals((await replayed.json()).code, "REPLAY_REJECTED");
    assertEquals((await upload(report(2))).status, 200);
    const changed = report(2);
    changed.fixes[0].accuracyM = 19;
    const conflicting = await upload(changed);
    assertEquals(conflicting.status, 409);
    assertEquals((await conflicting.json()).code, "IDEMPOTENCY_CONFLICT");

    // 4. Foreign family is denied; membership loss takes effect immediately.
    assertEquals((await read(outsider)).status, 403);
    await sql`update public.family_members set status='removed' where family_id=${family.familyId}::uuid and user_id=${removable}::uuid`;
    assertEquals((await read(removable)).status, 403);
    assertEquals((await readOk(owner)).state, "available");

    // 5. Authority fields and impossible positions are refused before any write.
    const authority = await report$(
      (await signed("report-device-location", {
        ...report(3),
        familyId: family.familyId,
      }))(),
    );
    assertEquals(authority.status, 400);
    const impossible = report(3);
    impossible.fixes[0].longitude = 181;
    assertEquals((await upload(impossible)).status, 400);

    // 6. Permission loss is reported as that, never as a position or as "safe".
    assertEquals(
      (await upload(report(3, { status: "permission_lost", fixes: [] })))
        .status,
      200,
    );
    const lost = await readOk(owner);
    assertEquals(lost.status, "permission_lost");
    assertEquals(
      lost.fixes.length,
      2,
      "earlier fixes remain, status explains why nothing is new",
    );

    // 7. Clear, delayed replay of an older report, then a deliberate newer report.
    const cleared = await clear$(
      (await signed("clear-device-location", {
        version: 1,
        epochId: EPOCH,
        sequence: 4,
      }))(),
    );
    assertEquals(cleared.status, 200);
    assertEquals((await readOk(owner)).state, "none");
    const delayed = await upload(report(3));
    assertEquals(delayed.status, 409);
    assertEquals((await delayed.json()).code, "STALE_VERSION");
    const checkpoint = await checkpoint$(
      (await signed("get-device-location-checkpoint", { version: 1 }))(),
    );
    assertEquals(await checkpoint.json(), { sequence: 4, epochId: EPOCH });
    assertEquals((await upload(report(9))).status, 200);
    assertEquals((await readOk(owner)).state, "available");

    // 8. Retention: an expired status reads as expired; the purge removes the rows but keeps the sequence.
    await sql`update private.device_location_status set expires_at=clock_timestamp()-interval '1 second' where device_id=${device.deviceId}::uuid`;
    assertEquals((await readOk(owner)).state, "expired");
    await sql`update private.device_location_fixes set expires_at=clock_timestamp()-interval '1 second' where device_id=${device.deviceId}::uuid`;
    await sql`select private.harbor_purge_device_location()`;
    assertEquals(
      (await sql`select count(*)::int n from private.device_location_fixes where device_id=${device.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    assertEquals(
      (await (await checkpoint$(
        (await signed("get-device-location-checkpoint", { version: 1 }))(),
      )).json()).sequence,
      9,
    );

    // 9. Revocation removes the data and denies both sides at once.
    assertEquals((await upload(report(10))).status, 200);
    await revokeDeviceAtomic({
      deviceId: device.deviceId,
      familyId: family.familyId,
      actorUserId: owner,
    });
    for (const table of ["device_location_fixes", "device_location_status"]) {
      assertEquals(
        (await sql`select count(*)::int n from private.${
          sql(table)
        } where device_id=${device.deviceId}::uuid`)[0].n,
        0,
      );
    }
    const afterRevoke = await upload(report(11));
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
      (await sql`select count(*)::int n from private.device_location_checkpoints c join public.devices_public d on d.id=c.device_id where d.family_id = any(${families}::uuid[])`)[
        0
      ].n,
      0,
    );
    await sql.end();
  }
});
