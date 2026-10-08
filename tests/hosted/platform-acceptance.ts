// Opt-in hosted development acceptance. Never run in PR CI or against production.
// Pipe CLI API-key JSON through stdin; credentials stay in memory and are not logged.
import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.105.0";
import { createHmac } from "node:crypto";
import { assertEquals } from "jsr:@std/assert@1";

const projectRef = "bfvybxkjxilntjgndsrm";
const url = `https://${projectRef}.supabase.co`;
const origin = "https://harbor-lyart-nu.vercel.app";
const bytes = await new Response(Deno.stdin.readable).text();
const keys = JSON.parse(bytes) as Array<{ name: string; api_key: string }>;
const publicKey = keys.find((key) => key.name === "anon")?.api_key;
const serverKey = keys.find((key) => key.name === "service_role")?.api_key;
if (!publicKey || !serverKey?.startsWith("eyJ")) {
  throw new Error("CLI key input must include usable anon/service_role keys");
}
const options = {
  auth: {
    persistSession: false,
    autoRefreshToken: false,
    detectSessionInUrl: false,
  },
};
const admin = createClient(url, serverKey, options);
const clients: SupabaseClient[] = [];
const users: string[] = [], families: string[] = [];
const runId = crypto.randomUUID();
let deviceId: string | undefined;
function client() {
  const value = createClient(url, publicKey!, options);
  clients.push(value);
  return value;
}
function ok(error: { code?: string } | null, stage: string) {
  if (error) throw new Error(`${stage} failed (${error.code ?? "API error"})`);
}
function pass(stage: string) {
  console.log(`PASS ${stage}`);
}
async function token(value: SupabaseClient) {
  const session = await value.auth.getSession();
  ok(session.error, "session");
  if (!session.data.session) throw new Error("Missing fixture session");
  return session.data.session.access_token;
}
async function endpoint(
  value: SupabaseClient,
  name: string,
  body: unknown,
  extra: Record<string, string> = {},
) {
  const response = await fetch(`${url}/functions/v1/${name}`, {
    method: "POST",
    headers: {
      apikey: publicKey!,
      Authorization: `Bearer ${await token(value)}`,
      Origin: origin,
      "content-type": "application/json",
      ...extra,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  const data = response.status === 204 ? null : await response.json();
  assertEquals(
    response.headers.get("access-control-allow-origin"),
    origin,
    `${name} CORS`,
  );
  return { status: response.status, data };
}
async function parent(label: string) {
  const email = `harbor-acceptance-${label}-${runId}@example.invalid`;
  const password = `${crypto.randomUUID()}Aa1!`;
  const created = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    user_metadata: { harbor_acceptance_fixture: runId },
  });
  ok(created.error, "fixture parent create");
  users.push(created.data.user!.id);
  const value = client();
  const signed = await value.auth.signInWithPassword({ email, password });
  ok(signed.error, "parent password login");
  return value;
}
function totp(secret: string, epoch = Math.floor(Date.now() / 1000)) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let buffer = 0, bits = 0;
  const key: number[] = [];
  for (const char of secret.toUpperCase().replace(/=+$/, "")) {
    const digit = alphabet.indexOf(char);
    if (digit < 0) throw new Error("Invalid TOTP secret encoding");
    buffer = (buffer << 5) | digit;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      key.push((buffer >>> bits) & 255);
    }
  }
  const counter = new Uint8Array(8);
  new DataView(counter.buffer).setBigUint64(0, BigInt(Math.floor(epoch / 30)));
  const digest = createHmac("sha1", new Uint8Array(key)).update(counter)
    .digest();
  const offset = digest[19] & 15;
  const number = ((digest[offset] & 127) << 24) | (digest[offset + 1] << 16) |
    (digest[offset + 2] << 8) | digest[offset + 3];
  return String(number % 1000000).padStart(6, "0");
}
assertEquals(totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59), "287082");
async function join(
  value: SupabaseClient,
  topic: string,
  privateChannel = true,
) {
  await value.realtime.setAuth(await token(value));
  const channel = value.channel(topic, { config: { private: privateChannel } })
    .on("broadcast", { event: "changed" }, () => {});
  try {
    return await new Promise<string>((resolve) => {
      const timeout = setTimeout(() => resolve("ACCEPTANCE_TIMEOUT"), 15000);
      channel.subscribe((status, error) => {
        if (error) {
          console.log(
            `Realtime join ${status}: ${
              error.message.replace(/eyJ[A-Za-z0-9._-]+/g, "[redacted]").slice(
                0,
                300,
              )
            }`,
          );
        }
        if (
          status === "SUBSCRIBED" || status === "CHANNEL_ERROR" ||
          status === "TIMED_OUT"
        ) {
          clearTimeout(timeout);
          resolve(status);
        }
      });
    });
  } finally {
    await value.removeChannel(channel);
    value.realtime.disconnect();
  }
}
const encode = (value: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(value)));
try {
  const a = await parent("a"), b = await parent("b"), child = client();
  const anonymous = await child.auth.signInAnonymously({
    options: { data: { harbor_acceptance_fixture: runId } },
  });
  ok(anonymous.error, "child anonymous login");
  users.push(anonymous.data.user!.id);
  pass("real parent password and anonymous child login");
  const aFamily = await endpoint(a, "create-family", {
    name: "Hosted acceptance A",
    idempotencyKey: runId,
  });
  assertEquals(aFamily.status, 200, "create-family status");
  families.push(aFamily.data.familyId);
  assertEquals(
    (await endpoint(a, "create-family", {
      name: "Hosted acceptance A",
      idempotencyKey: runId,
    })).data.familyId,
    families[0],
  );
  const bFamily = await endpoint(b, "create-family", {
    name: "Hosted acceptance B",
    idempotencyKey: runId,
  });
  assertEquals(bFamily.status, 200);
  families.push(bFamily.data.familyId);
  pass("deployed family creation and idempotency through hosted database");
  for (const [value, expected] of [[a, 1], [b, 0], [child, 0]] as const) {
    const read = await value.from("families").select("id").eq(
      "id",
      families[0],
    );
    ok(read.error, "family RLS read");
    assertEquals(read.data!.length, expected);
  }
  pass("cross-family and child Data API isolation");
  const childRow = await admin.from("children").insert({
    family_id: families[0],
    display_name: "Hosted acceptance fixture",
  }).select("id").single();
  ok(childRow.error, "child fixture insert");
  const pairing = await endpoint(a, "create-device-pairing", {
    childId: childRow.data!.id,
  });
  assertEquals(pairing.status, 200);
  const deviceKeys = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const claimed = await endpoint(child, "device-claim", {
    code: pairing.data.code,
    publicKeySpki: encode(
      await crypto.subtle.exportKey("spki", deviceKeys.publicKey),
    ),
    device: { displayName: "Hosted acceptance", supervisionMode: "standard" },
  });
  assertEquals(claimed.status, 200);
  deviceId = claimed.data.deviceId;
  async function proof() {
    const body = {},
      bodyText = JSON.stringify(body),
      epoch = String(Math.floor(Date.now() / 1000)),
      nonce = crypto.randomUUID();
    const hash = [
      ...new Uint8Array(
        await crypto.subtle.digest(
          "SHA-256",
          new TextEncoder().encode(bodyText),
        ),
      ),
    ].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const canonical = ["POST", "device-sync", deviceId, hash, epoch, nonce]
      .join("\n");
    const signature = encode(
      await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        deviceKeys.privateKey,
        new TextEncoder().encode(canonical),
      ),
    );
    return {
      body,
      headers: {
        "X-Harbor-Device-Id": deviceId!,
        "X-Harbor-Timestamp": epoch,
        "X-Harbor-Nonce": nonce,
        "X-Harbor-Signature": signature,
      },
    };
  }
  const signed = await proof();
  assertEquals(
    (await endpoint(child, "device-sync", signed.body, signed.headers)).status,
    200,
  );
  const replay = await endpoint(
    child,
    "device-sync",
    signed.body,
    signed.headers,
  );
  assertEquals(replay.status, 409);
  assertEquals(replay.data.code, "REPLAY_REJECTED");
  pass("live pairing, P-256 device proof and persisted replay denial");
  const revokeBody = { deviceId, familyId: families[0] };
  const aal1 = await endpoint(a, "revoke-device", revokeBody);
  assertEquals(aal1.status, 403);
  assertEquals(aal1.data.code, "MFA_REQUIRED");
  const enrollment = await a.auth.mfa.enroll({
    factorType: "totp",
    friendlyName: "Hosted acceptance",
  });
  ok(enrollment.error, "TOTP enrollment");
  const verified = await a.auth.mfa.challengeAndVerify({
    factorId: enrollment.data!.id,
    code: totp(enrollment.data!.totp.secret),
  });
  ok(verified.error, "TOTP challenge/verify");
  const assurance = await a.auth.mfa.getAuthenticatorAssuranceLevel();
  ok(assurance.error, "MFA assurance");
  assertEquals(assurance.data!.currentLevel, "aal2");
  pass("real hosted TOTP enrollment and AAL2 challenge");
  const topic = `family:${families[0]}`;
  assertEquals(await join(a, topic), "SUBSCRIBED", "own family private join");
  assertEquals(
    await join(b, topic),
    "CHANNEL_ERROR",
    "other parent private denial",
  );
  assertEquals(
    await join(child, topic),
    "CHANNEL_ERROR",
    "child private denial",
  );
  pass("private WebSocket family authorization");
  const otherRevoke = await endpoint(b, "revoke-device", revokeBody);
  assertEquals(otherRevoke.status, 403);
  if (Deno.args.includes("--stale-mfa")) {
    // Real hosted token and wall-clock age; never forge claims or shift server time.
    const accessToken = await token(a);
    const claims = JSON.parse(atob(accessToken.split(".")[1]));
    const mfaTimes = (claims.amr ?? [])
      .filter((event: { method: string }) => event.method === "totp")
      .map((event: { timestamp: number }) => event.timestamp);
    const latestMfa = Math.max(...mfaTimes);
    if (!Number.isFinite(latestMfa)) {
      throw new Error("Missing real MFA timestamp");
    }
    const staleAt = latestMfa + 902;
    if (claims.exp <= staleAt + 60) {
      throw new Error("Fixture token expires before stale-MFA acceptance");
    }
    console.log("WAIT real hosted MFA session aging beyond 900 seconds");
    while (Math.floor(Date.now() / 1000) < staleAt) {
      const remaining = staleAt * 1000 - Date.now();
      await new Promise((resolve) =>
        setTimeout(resolve, Math.min(30000, remaining))
      );
    }
    const stillValid = await a.auth.getUser(accessToken);
    ok(stillValid.error, "unexpired stale AAL2 token validation");
    assertEquals(stillValid.data.user?.id, verified.data!.user.id);
    const stale = await endpoint(a, "revoke-device", revokeBody);
    assertEquals(stale.status, 403, "stale AAL2 revocation denial");
    assertEquals(stale.data.code, "MFA_REQUIRED");
    const unaffected = await proof();
    assertEquals(
      (await endpoint(
        child,
        "device-sync",
        unaffected.body,
        unaffected.headers,
      )).status,
      200,
      "stale MFA denial must not revoke the device",
    );
    pass("real stale (>900s) AAL2 rejected without revocation");
    const refreshed = await a.auth.mfa.challengeAndVerify({
      factorId: enrollment.data!.id,
      code: totp(enrollment.data!.totp.secret),
    });
    ok(refreshed.error, "fresh TOTP challenge after stale denial");
  }
  assertEquals((await endpoint(a, "revoke-device", revokeBody)).status, 204);
  const after = await proof();
  const revoked = await endpoint(
    child,
    "device-sync",
    after.body,
    after.headers,
  );
  assertEquals(revoked.status, 403);
  assertEquals(revoked.data.code, "DEVICE_REVOKED");
  pass("fresh MFA revocation and immediate rejection of still-valid child JWT");
  assertEquals(
    await join(b, `harbor-public-probe-${runId}`, false),
    "CHANNEL_ERROR",
    "public channels must be disabled",
  );
  pass("public Realtime channels disabled");
} finally {
  // Cleanup is restricted to IDs created during this invocation. Keep audit evidence.
  for (const value of clients) {
    await value.removeAllChannels();
    value.realtime.disconnect();
    await value.auth.signOut();
  }
  for (const id of families) {
    const result = await admin.from("families").delete().eq("id", id);
    ok(result.error, "family cleanup");
  }
  for (const id of users) {
    const result = await admin.auth.admin.deleteUser(id);
    ok(result.error, "Auth fixture cleanup");
  }
  console.log(
    JSON.stringify({
      projectRef,
      runId,
      users,
      families,
      deviceId,
      fixturesRemoved: true,
    }),
  );
}
