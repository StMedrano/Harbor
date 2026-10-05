// Opt-in development operator. Imported helpers perform no hosted calls.
import { notificationRoute } from "../../supabase/functions/_shared/notification.ts";
import type { NotificationRouteRefV1 } from "../../packages/contracts/src/v1/notifications.ts";
import type { DeliveryOutcome } from "../../supabase/functions/_shared/outbox-dispatch.ts";

export const projectRef = "bfvybxkjxilntjgndsrm";
export const developmentUrl = `https://${projectRef}.supabase.co`;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw Error("Invalid operator input");
  }
  return value as Record<string, unknown>;
}
function id(value: unknown): string {
  if (typeof value !== "string" || !uuid.test(value)) {
    throw Error("Invalid fixture identifier");
  }
  return value;
}
export type FixtureManifest = {
  runId: string;
  projectRef: string;
  parentUserId: string;
  familyId: string;
  childId: string;
  deviceId?: string;
  childAuthUserId?: string;
  expectedDesiredStateVersion: number;
  subscriptionIds: string[];
};
export function validateManifest(value: unknown): FixtureManifest {
  const m = object(value);
  if (m.projectRef !== projectRef) throw Error("Wrong development project");
  if (
    !Number.isSafeInteger(m.expectedDesiredStateVersion) ||
    (m.expectedDesiredStateVersion as number) < 0 ||
    !Array.isArray(m.subscriptionIds)
  ) throw Error("Invalid fixture state");
  const subscriptions = m.subscriptionIds.map(id);
  if (new Set(subscriptions).size !== subscriptions.length) {
    throw Error("Duplicate fixture subscriptions");
  }
  return {
    projectRef,
    runId: id(m.runId),
    parentUserId: id(m.parentUserId),
    familyId: id(m.familyId),
    childId: id(m.childId),
    ...(m.deviceId === undefined ? {} : { deviceId: id(m.deviceId) }),
    ...(m.childAuthUserId === undefined
      ? {}
      : { childAuthUserId: id(m.childAuthUserId) }),
    expectedDesiredStateVersion: m.expectedDesiredStateVersion as number,
    subscriptionIds: subscriptions,
  };
}
export type OutboxRow = {
  id: string;
  eventKey: string;
  transport: "fcm" | "web_push";
  targetRef: Record<string, string>;
  route: NotificationRouteRefV1;
};
export function validateDispatchRows(
  value: FixtureManifest,
  input: unknown,
): OutboxRow[] {
  const m = validateManifest(value);
  if (
    !m.deviceId || m.expectedDesiredStateVersion < 1 || !Array.isArray(input)
  ) throw Error("Claimed versioned fixture required");
  const deviceId = m.deviceId;
  const eventKey =
    `desired-state:${m.deviceId}:${m.expectedDesiredStateVersion}`;
  const seen = new Set<string>(), targets = new Set<string>();
  const rows = input.map((raw) => {
    const r = object(raw),
      rowId = id(r.id),
      route = notificationRoute(r.route_payload),
      target = object(r.target_ref);
    if (
      seen.has(rowId) || r.event_key !== eventKey || !route ||
      Object.keys(route).length !== 5 ||
      route.kind !== "device.state.changed" ||
      route.familyId !== m.familyId || route.childId !== m.childId ||
      route.deviceId !== m.deviceId ||
      Object.keys(target).length !== 1
    ) throw Error("Foreign or malformed outbox row");
    seen.add(rowId);
    let targetRef: Record<string, string>;
    if (r.transport === "fcm" && target.deviceId === deviceId) {
      targetRef = { deviceId };
    } else if (
      r.transport === "web_push" && typeof target.subscriptionId === "string" &&
      m.subscriptionIds.includes(target.subscriptionId)
    ) targetRef = { subscriptionId: target.subscriptionId };
    else throw Error("Foreign recipient target");
    const targetKey = `${r.transport}:${Object.values(targetRef)[0]}`;
    if (targets.has(targetKey)) throw Error("Duplicate recipient intent");
    targets.add(targetKey);
    return {
      id: rowId,
      eventKey,
      transport: r.transport as "fcm" | "web_push",
      targetRef,
      route,
    };
  });
  if (
    rows.length !== m.subscriptionIds.length + 1 ||
    !targets.has(`fcm:${m.deviceId}`)
  ) throw Error("Incomplete fixture transport batch");
  return rows;
}
export async function dispatchRows(
  manifest: FixtureManifest,
  rows: unknown,
  workerKey: string,
  send: (id: string, key: string) => Promise<DeliveryOutcome>,
) {
  const validated = validateDispatchRows(manifest, rows);
  if (!workerKey?.trim()) throw Error("Missing workerKey input");
  const outcomes = [];
  for (const row of validated) {
    try {
      const outcome = await send(row.id, workerKey);
      if (!["sent", "retry", "dead_letter", "no_op"].includes(outcome.status)) {
        throw Error("Invalid worker outcome");
      }
      outcomes.push({
        id: row.id,
        transport: row.transport,
        status: outcome.status,
      });
    } catch {
      outcomes.push({
        id: row.id,
        transport: row.transport,
        status: "unverified" as const,
      });
    }
  }
  return outcomes;
}
export function readiness(input: Record<string, unknown>) {
  const required = [
    "publishableKey",
    "serviceRoleKey",
    "workerKey",
    "password",
  ];
  return {
    missing: required.filter((name) =>
      typeof input[name] !== "string" || !(input[name] as string).trim()
    ),
  };
}
export async function cleanupStages(
  manifest: FixtureManifest,
  stages: { name: string; run(): Promise<void> }[],
) {
  const m = validateManifest(manifest), failed: string[] = [];
  for (const stage of stages) {
    try {
      await stage.run();
    } catch {
      failed.push(stage.name);
    }
  }
  return {
    complete: failed.length === 0,
    failed,
    remainingFixtureIds: failed.length
      ? [
        m.parentUserId,
        m.familyId,
        m.childId,
        ...(m.deviceId ? [m.deviceId] : []),
        ...(m.childAuthUserId ? [m.childAuthUserId] : []),
        ...m.subscriptionIds,
      ]
      : [],
  };
}
export type PreparationJournal = {
  runId: string;
  projectRef: string;
  parentUserId?: string;
  familyId?: string;
  childId?: string;
};
export async function prepareFixture(ref: string, runId: string, deps: {
  protect(): Promise<void>;
  parent(): Promise<string>;
  family(parentId: string): Promise<string>;
  child(familyId: string): Promise<string>;
  pairing(childId: string): Promise<void>;
  checkpoint(journal: PreparationJournal): Promise<void>;
  rollback(journal: PreparationJournal): Promise<void>;
}): Promise<FixtureManifest> {
  if (ref !== projectRef) throw Error("Wrong development project");
  const journal: PreparationJournal = { projectRef, runId: id(runId) };
  await deps.protect(); // Must fail before creating anything if handoff protection fails.
  try {
    await deps.checkpoint({ ...journal });
    journal.parentUserId = id(await deps.parent());
    await deps.checkpoint({ ...journal });
    journal.familyId = id(await deps.family(journal.parentUserId));
    await deps.checkpoint({ ...journal });
    journal.childId = id(await deps.child(journal.familyId));
    await deps.checkpoint({ ...journal });
    await deps.pairing(journal.childId);
    return validateManifest({
      ...journal,
      expectedDesiredStateVersion: 0,
      subscriptionIds: [],
    });
  } catch {
    try {
      await deps.rollback({ ...journal });
    } catch {
      throw Error(
        "Preparation failed; cleanup incomplete, retain recovery journal",
      );
    }
    throw Error(
      "Preparation failed; reconcile uncertain API results using recovery journal",
    );
  }
}

// This directory is ignored and never served by the browser preview.
const operatorRoot = new URL(
  "../../.superpowers/notification-operator/",
  import.meta.url,
);
const localFile = (name: string) => new URL(name, operatorRoot);
async function protectDirectory() {
  await Deno.mkdir(operatorRoot, { recursive: true, mode: 0o700 });
  const repository = await Deno.realPath(new URL("../../", import.meta.url));
  const resolved = await Deno.realPath(operatorRoot);
  if (
    !resolved.startsWith(
      repository + (Deno.build.os === "windows" ? "\\" : "/"),
    )
  ) throw Error("Operator directory outside repository");
  const info = await Deno.lstat(operatorRoot);
  if (!info.isDirectory || info.isSymlink) {
    throw Error("Unsafe operator directory");
  }
  if (Deno.build.os === "windows") {
    // Fixed script, path passed as a positional argument; no input interpolation.
    const script =
      `$ErrorActionPreference='Stop'; $p=$args[0]; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; $acl=New-Object Security.AccessControl.DirectorySecurity; $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false); foreach($s in @($sid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}; Set-Acl -LiteralPath $p -AclObject $acl; $check=Get-Acl -LiteralPath $p; if(!$check.AreAccessRulesProtected){throw 'Protection failed'}; foreach($r in $check.Access){$actual=$r.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value; if($actual -ne $sid.Value -and $actual -ne 'S-1-5-18'){throw 'Unexpected access'}}`;
    // A script file avoids PowerShell -Command argument reparsing.
    const path = await Deno.makeTempFile({ suffix: ".ps1" });
    try {
      await Deno.writeTextFile(path, script);
      const result = await new Deno.Command("powershell.exe", {
        args: [
          "-NoProfile",
          "-NonInteractive",
          "-File",
          path,
          await Deno.realPath(operatorRoot),
        ],
        stdout: "null",
        stderr: "null",
      }).output();
      if (!result.success) {
        throw Error("Cannot establish restricted operator permissions");
      }
    } finally {
      await Deno.remove(path);
    }
  } else {
    await Deno.chmod(operatorRoot, 0o700);
    if (((await Deno.stat(operatorRoot)).mode! & 0o777) !== 0o700) {
      throw Error("Cannot protect operator directory");
    }
  }
}
async function save(name: string, value: unknown) {
  const file = localFile(name);
  try {
    if ((await Deno.lstat(file)).isSymlink) throw Error("Unsafe operator file");
  } catch (e) {
    if (!(e instanceof Deno.errors.NotFound)) throw e;
  }
  await Deno.writeTextFile(file, JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  if (Deno.build.os !== "windows") await Deno.chmod(file, 0o600);
}
async function load(name: string) {
  if ((await Deno.lstat(localFile(name))).isSymlink) {
    throw Error("Unsafe operator file");
  }
  return JSON.parse(await Deno.readTextFile(localFile(name)));
}
async function main() {
  const command = Deno.args[0];
  if (!["prepare", "change-state", "dispatch", "cleanup"].includes(command)) {
    throw Error("Choose prepare, change-state, dispatch or cleanup");
  }
  const input = object(
    JSON.parse(await new Response(Deno.stdin.readable).text()),
  );
  if (input.projectRef !== projectRef || input.supabaseUrl !== developmentUrl) {
    throw Error("Wrong development configuration");
  }
  if (
    typeof input.publishableKey !== "string" ||
    !/^sb_publishable_[A-Za-z0-9_-]+$/.test(input.publishableKey)
  ) throw Error("Missing publishableKey input");
  await protectDirectory();
  if (command === "dispatch") {
    const m = validateManifest(await load("manifest.json"));
    const outcomes = await dispatchRows(
      m,
      input.rows,
      typeof input.workerKey === "string" ? input.workerKey : "",
      async (outboxId, key) => {
        const response = await fetch(
          `${developmentUrl}/functions/v1/dispatch-outbox`,
          {
            method: "POST",
            headers: { apikey: key, "content-type": "application/json" },
            body: JSON.stringify({ outboxId }),
            signal: AbortSignal.timeout(20000),
          },
        );
        if (!response.ok) throw Error("Worker request failed");
        return await response.json();
      },
    );
    await save("dispatch.json", {
      at: new Date().toISOString(),
      eventVersion: m.expectedDesiredStateVersion,
      outcomes,
    });
    console.log(
      JSON.stringify({
        command,
        outcomes: outcomes.map(({ transport, status }) => ({
          transport,
          status,
        })),
        receipt: "unverified",
      }),
    );
    return;
  }
  if (
    typeof input.serviceRoleKey !== "string" || !input.serviceRoleKey.trim()
  ) throw Error("Missing serviceRoleKey input");
  const { createClient } = await import("npm:@supabase/supabase-js@2.105.0");
  const options = {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
  };
  const admin = createClient(developmentUrl, input.serviceRoleKey, options);
  const parent = createClient(developmentUrl, input.publishableKey, options);
  const ok = (error: unknown) => {
    if (error) throw Error("Hosted fixture operation failed");
  };
  const signIn = async (
    credentials: { email: string; password: string },
    expected: string,
    runId: string,
  ) => {
    const login = await parent.auth.signInWithPassword(credentials);
    ok(login.error);
    const user = login.data.user;
    if (
      !user || user.id !== expected || user.is_anonymous ||
      user.user_metadata.harbor_acceptance_fixture !== runId
    ) throw Error("Wrong disposable parent identity");
  };
  const endpoint = async (name: string, body: unknown) => {
    const session = await parent.auth.getSession();
    ok(session.error);
    if (!session.data.session) throw Error("Fixture sign-in required");
    const response = await fetch(`${developmentUrl}/functions/v1/${name}`, {
      method: "POST",
      headers: {
        apikey: input.publishableKey as string,
        Authorization: `Bearer ${session.data.session.access_token}`,
        Origin: "http://localhost:3000",
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    });
    if (!response.ok) throw Error("Hosted endpoint operation failed");
    return response.status === 204 ? null : await response.json();
  };
  try {
    if (command === "prepare") {
      try {
        await Deno.stat(localFile("journal.json"));
        throw Error(
          "Existing journal must be recovered or cleaned before prepare",
        );
      } catch (e) {
        if (!(e instanceof Deno.errors.NotFound)) throw e;
      }
      const reuse = input.parent === undefined
        ? undefined
        : object(input.parent);
      const runId = reuse ? id(reuse.runId) : crypto.randomUUID();
      const credentials = reuse
        ? {
          email: String(reuse.email ?? ""),
          password: String(reuse.password ?? ""),
        }
        : {
          email: `harbor-notification-${runId}@example.invalid`,
          password: `${crypto.randomUUID()}Aa1!`,
        };
      let parentId: string | undefined;
      const manifest = await prepareFixture(projectRef, runId, {
        protect: async () => {
          await save("handoff.json", {
            ...credentials,
            supabaseUrl: developmentUrl,
            publishableKey: input.publishableKey,
          });
        },
        parent: async () => {
          if (reuse) {
            parentId = id(reuse.userId);
            await signIn(credentials, parentId, runId);
            const memberships = await admin.from("family_members").select("id")
              .eq("user_id", parentId);
            ok(memberships.error);
            if (memberships.data?.length) {
              throw Error("Reuse requires an unassigned disposable parent");
            }
          } else {
            const created = await admin.auth.admin.createUser({
              ...credentials,
              email_confirm: true,
              user_metadata: { harbor_acceptance_fixture: runId },
            });
            ok(created.error);
            parentId = id(created.data.user?.id);
          }
          return parentId;
        },
        family: async (userId) => {
          await signIn(credentials, userId, runId);
          return id(
            (await endpoint("create-family", {
              name: "Notification acceptance fixture",
              idempotencyKey: runId,
            })).familyId,
          );
        },
        child: async (familyId) => {
          const created = await admin.from("children").insert({
            family_id: familyId,
            display_name: "Notification acceptance fixture",
          }).select("id").single();
          ok(created.error);
          return id(created.data?.id);
        },
        pairing: async (childId) => {
          const pairing = await endpoint("create-device-pairing", { childId });
          if (
            typeof pairing.code !== "string" || !/^\d{6}$/.test(pairing.code)
          ) throw Error("Invalid pairing response");
          await save("handoff.json", {
            ...credentials,
            supabaseUrl: developmentUrl,
            publishableKey: input.publishableKey,
            pairingCode: pairing.code,
            expiresAt: pairing.expiresAt,
          });
        },
        checkpoint: async (journal) => {
          await save("journal.json", journal);
          if (journal.childId) {
            await save(
              "manifest.json",
              validateManifest({
                ...journal,
                expectedDesiredStateVersion: 0,
                subscriptionIds: [],
              }),
            );
          }
        },
        rollback: async (journal) => {
          const failed: string[] = [];
          if (!journal.parentUserId) failed.push("uncertain-parent-creation");
          // Parent marker was verified before assignment. Recover an idempotent family even if its HTTP reply was lost.
          const families = journal.parentUserId
            ? await admin.from("family_members").select("family_id").eq(
              "user_id",
              journal.parentUserId,
            )
            : { data: [], error: null };
          if (families.error) failed.push("recover-family");
          for (const family of families.data ?? []) {
            const removed = await admin.from("families").delete().eq(
              "id",
              id(family.family_id),
            );
            if (removed.error) failed.push("family");
          }
          if (journal.parentUserId) {
            const removed = await admin.auth.admin.deleteUser(
              journal.parentUserId,
            );
            if (removed.error) failed.push("parent-auth");
          }
          await save("cleanup.json", {
            complete: failed.length === 0,
            failed,
            journal,
          });
          if (failed.length) throw Error("Rollback incomplete");
          await Deno.remove(localFile("handoff.json"));
        },
      });
      validateManifest(manifest); // The protected checkpoint already persisted it inside the rollback boundary.
      console.log(
        JSON.stringify({
          command,
          prepared: true,
          handoff: "restricted local handoff.json",
          pairing: "pending Android claim",
        }),
      );
      return;
    }
    const m = validateManifest(await load("manifest.json"));
    const handoff = object(await load("handoff.json"));
    const parentRecord = await admin.auth.admin.getUserById(m.parentUserId);
    if (parentRecord.error && parentRecord.error.status !== 404) {
      ok(parentRecord.error);
    }
    const parentExists = !!parentRecord.data.user;
    if (parentExists) {
      if (
        parentRecord.data.user!.user_metadata.harbor_acceptance_fixture !==
          m.runId
      ) throw Error("Foreign parent fixture");
      await signIn(
        { email: String(handoff.email), password: String(handoff.password) },
        m.parentUserId,
        m.runId,
      );
    } else if (command !== "cleanup") throw Error("Fixture parent missing");
    const family = await admin.from("families").select("id").eq(
      "id",
      m.familyId,
    ).maybeSingle();
    ok(family.error);
    if (family.data) {
      if (!parentExists) {
        throw Error("Family requires trusted recovery after parent removal");
      }
      const member = await admin.from("family_members").select("role").eq(
        "family_id",
        m.familyId,
      ).eq("user_id", m.parentUserId).eq("role", "owner").single();
      ok(member.error);
      const child = await admin.from("children").select("id").eq(
        "id",
        m.childId,
      ).eq("family_id", m.familyId).single();
      ok(child.error);
    } else if (command !== "cleanup") throw Error("Fixture family missing");
    if (input.deviceId !== undefined) {
      const deviceId = id(input.deviceId);
      if (m.deviceId && m.deviceId !== deviceId) {
        throw Error("Fixture device already bound");
      }
      const device = await admin.from("devices_public").select("id").eq(
        "id",
        deviceId,
      ).eq("family_id", m.familyId).eq("child_id", m.childId).single();
      ok(device.error);
      m.deviceId = deviceId;
      // Supplied only from a trusted, filtered private.device_security lookup.
      if (input.childAuthUserId !== undefined) {
        const binding = object(input.bindingEvidence);
        if (
          binding.projectRef !== projectRef || binding.deviceId !== deviceId ||
          binding.familyId !== m.familyId || binding.childId !== m.childId ||
          binding.authUserId !== input.childAuthUserId
        ) throw Error("Trusted exact-device private binding evidence required");
        m.childAuthUserId = id(input.childAuthUserId);
      }
      await save("manifest.json", m);
    }
    if (input.subscriptionIds !== undefined) {
      const updated = validateManifest({
        ...m,
        subscriptionIds: input.subscriptionIds,
      });
      m.subscriptionIds = updated.subscriptionIds; // Whitelist from trusted, parent-filtered SQL evidence.
      await save("manifest.json", m);
    }
    if (command === "change-state") {
      if (!m.deviceId || !m.childAuthUserId) {
        throw Error("Missing claimed device / trusted child Auth binding");
      }
      const auth = await admin.auth.admin.getUserById(m.childAuthUserId);
      ok(auth.error);
      if (!auth.data.user?.is_anonymous) {
        throw Error("Child Auth fixture must be anonymous");
      }
      const changedAt = new Date().toISOString();
      const result = await endpoint("update-device-state", {
        deviceId: m.deviceId,
        familyId: m.familyId,
        desiredState: { acceptanceRun: m.runId },
        expectedVersion: m.expectedDesiredStateVersion,
      });
      if (result.desiredStateVersion !== m.expectedDesiredStateVersion + 1) {
        throw Error(
          "Unexpected desired-state version; inspect trusted state before retry",
        );
      }
      m.expectedDesiredStateVersion = result.desiredStateVersion;
      await save("manifest.json", m);
      await save("change.json", {
        changedAt,
        version: m.expectedDesiredStateVersion,
      });
      console.log(
        JSON.stringify({
          command,
          version: m.expectedDesiredStateVersion,
          outbox: "inspect through trusted filtered SQL",
        }),
      );
      return;
    }
    const result = await cleanupStages(m, [
      {
        name: "browser",
        run: async () => {
          if (input.browserUnsubscribed !== true) {
            throw Error(
              "Local browser removal/unsubscribe confirmation required",
            );
          }
        },
      },
      {
        name: "revoke",
        run: async () => {
          if (!m.deviceId) return;
          const device = await admin.from("devices_public").select("status").eq(
            "id",
            m.deviceId,
          ).eq("family_id", m.familyId).eq("child_id", m.childId).maybeSingle();
          ok(device.error);
          if (!device.data || device.data.status === "revoked") return;
          const { createHmac } = await import("node:crypto");
          const enrollment = await parent.auth.mfa.enroll({
            factorType: "totp",
            friendlyName: "Notification acceptance cleanup",
          });
          ok(enrollment.error);
          const secret = enrollment.data!.totp.secret,
            alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
          let bits = 0, buffer = 0;
          const key: number[] = [];
          for (const char of secret.toUpperCase().replace(/=+$/, "")) {
            const digit = alphabet.indexOf(char);
            if (digit < 0) throw Error("Invalid TOTP");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
              bits -= 8;
              key.push((buffer >>> bits) & 255);
            }
          }
          const counter = new Uint8Array(8);
          new DataView(counter.buffer).setBigUint64(
            0,
            BigInt(Math.floor(Date.now() / 30000)),
          );
          const digest = createHmac("sha1", new Uint8Array(key)).update(counter)
              .digest(),
            offset = digest[19] & 15;
          const code = String(
            (((digest[offset] & 127) << 24) | (digest[offset + 1] << 16) |
              (digest[offset + 2] << 8) | digest[offset + 3]) % 1000000,
          ).padStart(6, "0");
          const verified = await parent.auth.mfa.challengeAndVerify({
            factorId: enrollment.data!.id,
            code,
          });
          ok(verified.error);
          await endpoint("revoke-device", {
            deviceId: m.deviceId,
            familyId: m.familyId,
          });
        },
      },
      {
        name: "private-rows",
        run: async () => {
          const evidence = object(input.databaseCleanup);
          if (
            evidence.projectRef !== projectRef || evidence.runId !== m.runId ||
            evidence.familyId !== m.familyId ||
            evidence.deviceId !== m.deviceId ||
            evidence.parentUserId !== m.parentUserId ||
            evidence.remainingOutbox !== 0 ||
            evidence.remainingSubscriptions !== 0
          ) throw Error("Trusted exact-fixture SQL cleanup evidence required");
        },
      },
      {
        name: "family",
        run: async () => {
          const deleted = await admin.from("families").delete().eq(
            "id",
            m.familyId,
          );
          ok(deleted.error);
        },
      },
      {
        name: "child-auth",
        run: async () => {
          if (!m.deviceId) return;
          if (!m.childAuthUserId) {
            throw Error("Missing trusted child Auth binding");
          }
          const user = await admin.auth.admin.getUserById(m.childAuthUserId);
          if (user.error?.status === 404) return;
          ok(user.error);
          if (!user.data.user?.is_anonymous) {
            throw Error("Refuse foreign Auth user");
          }
          const deleted = await admin.auth.admin.deleteUser(m.childAuthUserId);
          ok(deleted.error);
        },
      },
      {
        name: "parent-auth",
        run: async () => {
          if (!parentExists) return;
          const deleted = await admin.auth.admin.deleteUser(m.parentUserId);
          ok(deleted.error);
        },
      },
    ]);
    await save("cleanup.json", result);
    if (result.complete) await Deno.remove(localFile("handoff.json"));
    console.log(
      JSON.stringify({
        command,
        complete: result.complete,
        failed: result.failed,
        recovery: result.complete
          ? "none"
          : "retain journal and manifest; reconcile trusted SQL and Auth",
      }),
    );
    if (!result.complete) Deno.exitCode = 1;
  } finally {
    await parent.auth.signOut().catch(() => {});
  }
}
if (import.meta.main) {
  try {
    await main();
  } catch {
    console.error(
      "Operator command failed. Inspect restricted recovery files; no credentials or hosted response were printed.",
    );
    Deno.exitCode = 1;
  }
}
