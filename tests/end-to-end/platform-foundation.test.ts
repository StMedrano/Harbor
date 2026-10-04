import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { getPrivateSql, createFamilyAtomic, issueDevicePairingAtomic, claimDeviceAtomic, loadDeviceSecurity, claimDeviceRequestNonceAtomic, syncDeviceAtomic, registerDeviceFcmAtomic, registerParentWebPushAtomic, updateDeviceDesiredStateAtomic, revokeDeviceAtomic } from "../../supabase/functions/_shared/clients.ts";
import { requireDeviceProof } from "../../supabase/functions/_shared/device-proof.ts";
import { sha256Hex, verifyP256Sha256 } from "../../supabase/functions/_shared/crypto.ts";
import { requireRecentAal2 } from "../../supabase/functions/_shared/aal.ts";
import { createPersistentDispatchOne, privateOutboxStore } from "../../supabase/functions/_shared/outbox.ts";

Deno.test("two parent clients share durable device lifecycle with database authorization and real proof", async () => {
  const sql = getPrivateSql();
  const parentA = crypto.randomUUID(), parentB = crypto.randomUUID(), childAuth = crypto.randomUUID();
  const childId = crypto.randomUUID();
  const familyIds: string[] = [];
  const now = new Date();
  const epoch = Math.floor(now.getTime() / 1000);
  const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const encode = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
  // Logical clients share one parent subject. Live Auth issuance/transport is a separate hosted gate.
  const parentAndroid = { userId: parentA, accessToken: "parent-android-sim", aal: "aal2" as const, amr: [{ method: "totp", timestamp: epoch }] };
  const parentPwa = { ...parentAndroid, accessToken: "parent-pwa-sim" };
  try {
    for (const [id, anonymous] of [[parentA, false], [parentB, false], [childAuth, true]] as const) {
      await sql`insert into auth.users(id,is_anonymous) values (${id}::uuid,${anonymous})`;
    }
    const family = await createFamilyAtomic({ userId: parentAndroid.userId, name: "Acceptance A", idempotencyKey: "create-a" });
    familyIds.push(family.familyId);
    assertEquals(await createFamilyAtomic({ userId: parentPwa.userId, name: "Acceptance A", idempotencyKey: "create-a" }), family);
    const other = await createFamilyAtomic({ userId: parentB, name: "Acceptance B", idempotencyKey: "create-b" });
    familyIds.push(other.familyId);
    await sql`insert into public.children(id,family_id,display_name) values (${childId}::uuid,${family.familyId}::uuid,'Acceptance child')`;
    const codeDigest = await sha256Hex(`fixture-pepper:${crypto.randomUUID()}:123456`);
    await issueDevicePairingAtomic({ parentUserId: parentA, childId, codeDigest, expiresAt: new Date(now.getTime() + 600000).toISOString() });
    const device = await claimDeviceAtomic({ authUserId: childAuth, codeDigest, publicKeySpki: encode(await crypto.subtle.exportKey("spki", keys.publicKey)), displayName: "Acceptance device", model: null, androidVersion: null, supervisionMode: "full" });
    assertEquals(device.familyId, family.familyId);
    await assertRejects(() => claimDeviceAtomic({ authUserId: childAuth, codeDigest, publicKeySpki: "unused", displayName: "Duplicate", model: null, androidVersion: null, supervisionMode: "full" }));
    const proofDependencies = { now: () => now, requireDeviceIdentity: async () => ({ userId: childAuth, accessToken: "same-unexpired-child-token" }), loadDeviceSecurity, sha256: sha256Hex, verifyP256Signature: verifyP256Sha256, claimNonceAtomic: claimDeviceRequestNonceAtomic };
    const body = JSON.stringify({ acknowledgedDesiredStateVersion: null, appliedCommandIds: [] });
    const nonce = crypto.randomUUID();
    const canonical = ["POST", "device-sync", device.deviceId, await sha256Hex(body), String(epoch), nonce].join("\n");
    const signature = encode(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, keys.privateKey, new TextEncoder().encode(canonical)));
    const request = () => new Request("https://harbor.test/functions/v1/device-sync", { method: "POST", body, headers: { "X-Harbor-Device-Id": device.deviceId, "X-Harbor-Timestamp": String(epoch), "X-Harbor-Nonce": nonce, "X-Harbor-Signature": signature } });
    assertEquals((await requireDeviceProof(request(), "device-sync", proofDependencies)).deviceId, device.deviceId);
    const replay = await assertRejects(() => requireDeviceProof(request(), "device-sync", proofDependencies));
    assertEquals((replay as Error & { code: string }).code, "REPLAY_REJECTED");
    await syncDeviceAtomic({ deviceId: device.deviceId, acknowledgedDesiredStateVersion: null, appliedCommandIds: [] });
    await registerDeviceFcmAtomic({ deviceId: device.deviceId, token: `acceptance-token-${childAuth}` });
    await registerParentWebPushAtomic({ userId: parentPwa.userId, clientInstallationId: "parent-pwa-sim", endpoint: `https://push.example.test/${parentA}`, p256dh: "fixture-public", auth: "fixture-auth" });
    const desired = { paused: true, bedtime: "20:00" };
    const version = await updateDeviceDesiredStateAtomic({ deviceId: device.deviceId, familyId: family.familyId, actorUserId: parentAndroid.userId, desiredState: desired, expectedVersion: 0 });
    const synced = await syncDeviceAtomic({ deviceId: device.deviceId, acknowledgedDesiredStateVersion: null, appliedCommandIds: [] });
    assertEquals(synced.desiredState, desired);
    assertEquals(Number(synced.desiredStateVersion), 1);
    assertEquals(Number(version.desiredStateVersion), 1);
    const intents = await sql`select id, transport from private.notification_outbox where route_payload->>'deviceId' = ${device.deviceId} order by transport`;
    assertEquals(intents.map((row) => row.transport), ["fcm", "web_push"]);
    const dispatch = createPersistentDispatchOne(privateOutboxStore, { sendFcm: async () => ({ status: "sent" }), sendWebPush: async () => ({ status: "permanent_failure", reason: "invalid_subscription" }) });
    assertEquals(await dispatch(intents[1].id), { status: "dead_letter" });
    assertEquals(await dispatch(intents[0].id), { status: "sent" });
    assertEquals(await dispatch(intents[0].id), { status: "no_op" });
    const familyCount = async (userId: string, anonymous: boolean, target: string) => await sql.begin(async (tx) => {
      await tx`select set_config('request.jwt.claims',${JSON.stringify({ sub: userId, role: "authenticated", is_anonymous: anonymous })},true)`;
      await tx`set local role authenticated`;
      return Number((await tx`select count(*) as count from public.families where id = ${target}::uuid`)[0].count);
    });
    assertEquals(await familyCount(parentA, false, family.familyId), 1);
    assertEquals(await familyCount(parentB, false, family.familyId), 0);
    assertEquals(await familyCount(childAuth, true, family.familyId), 0);
    await assertRejects(() => updateDeviceDesiredStateAtomic({ deviceId: device.deviceId, familyId: family.familyId, actorUserId: parentB, desiredState: {}, expectedVersion: 1 }));
    try { requireRecentAal2({ ...parentPwa, amr: [{ method: "totp", timestamp: epoch - 901 }] }, epoch); throw new Error("stale MFA accepted"); }
    catch (error) { assertEquals((error as Error & { code: string }).code, "MFA_REQUIRED"); }
    requireRecentAal2(parentPwa, epoch);
    await revokeDeviceAtomic({ deviceId: device.deviceId, familyId: family.familyId, actorUserId: parentPwa.userId });
    const revoked = await assertRejects(() => requireDeviceProof(request(), "device-sync", proofDependencies));
    assertEquals((revoked as Error & { code: string }).code, "DEVICE_REVOKED");
    assertEquals(Number((await sql`select count(*) as count from private.audit_events where event_kind = 'device.revoked' and resource_id = ${device.deviceId}::uuid`)[0].count), 1);
  } finally {
    for (const id of familyIds) {
      await sql`delete from private.notification_outbox where route_payload->>'familyId' = ${id}`;
      await sql`delete from private.audit_events where family_id = ${id}::uuid`;
      await sql`delete from public.families where id = ${id}::uuid`;
    }
    await sql`delete from auth.users where id in (${parentA}::uuid,${parentB}::uuid,${childAuth}::uuid)`;
    await sql.end();
  }
});
