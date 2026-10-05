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
  return value.toLowerCase();
}
function sameId(value: unknown, expected: string): boolean {
  return typeof value === "string" && uuid.test(value) &&
    value.toLowerCase() === expected.toLowerCase();
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
export type EventMapping = {
  eventKey: string;
  desiredStateVersion: number;
  route: NotificationRouteRefV1;
  recipients: {
    id: string;
    transport: "fcm" | "web_push";
    targetRef: Record<string, string>;
  }[];
};
export function checkpointEvent(
  manifest: FixtureManifest,
  rows: OutboxRow[],
  existing: EventMapping[],
): EventMapping[] {
  const m = validateManifest(manifest);
  existing = validateHistory(m, existing);
  const validated = validateDispatchRows(m, rawRows(rows));
  if (!validated[0].route.resourceId) {
    throw Error("Legacy event cannot be checkpointed");
  }
  const next: EventMapping = {
    eventKey: validated[0].eventKey,
    desiredStateVersion: m.expectedDesiredStateVersion,
    route: validated[0].route,
    recipients: validated.map(({ id, transport, targetRef }) => ({
      id,
      transport,
      targetRef,
    })),
  };
  for (const old of existing) {
    if (old.desiredStateVersion === next.desiredStateVersion) {
      if (canonical(old) !== canonical(next)) {
        throw Error("Conflicting event checkpoint");
      }
    } else if (sameId(old.route.resourceId, next.route.resourceId!)) {
      throw Error("Reused event identity");
    }
  }
  return existing.some((old) =>
      old.desiredStateVersion === next.desiredStateVersion
    )
    ? existing
    : [...existing, next];
}
function validateHistory(m: FixtureManifest, input: unknown): EventMapping[] {
  if (!Array.isArray(input)) throw Error("Invalid event checkpoints");
  const existing = input as EventMapping[];
  const versions = new Set<number>(), resources = new Set<string>();
  for (const old of existing) {
    const record = object(old);
    if (
      Object.keys(record).sort().join() !==
        "desiredStateVersion,eventKey,recipients,route" ||
      !Number.isSafeInteger(record.desiredStateVersion) ||
      (record.desiredStateVersion as number) < 1 ||
      !Array.isArray(record.recipients)
    ) throw Error("Invalid event checkpoint");
    const subscriptions = record.recipients.flatMap((raw) => {
      const r = object(raw), target = object(r.targetRef);
      if (Object.keys(r).sort().join() !== "id,targetRef,transport") {
        throw Error("Invalid checkpoint recipient");
      }
      return r.transport === "web_push" ? [id(target.subscriptionId)] : [];
    });
    const historical = {
      ...m,
      expectedDesiredStateVersion: record.desiredStateVersion as number,
      subscriptionIds: subscriptions,
    };
    validateMapping(historical, old);
    const resource = old.route.resourceId!.toLowerCase();
    if (versions.has(old.desiredStateVersion) || resources.has(resource)) {
      throw Error("Duplicate event checkpoint");
    }
    versions.add(old.desiredStateVersion);
    resources.add(resource);
  }
  return existing;
}
function canonical(value: unknown): string {
  if (typeof value === "string") {
    return JSON.stringify(uuid.test(value) ? value.toLowerCase() : value);
  }
  if (Array.isArray(value)) return `[${value.map(canonical).sort().join(",")}]`;
  if (value && typeof value === "object") {
    return `{${
      Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map((
        [key, v],
      ) => `${JSON.stringify(key)}:${canonical(v)}`).join(",")
    }}`;
  }
  return JSON.stringify(value);
}
function rawRows(rows: OutboxRow[]) {
  if (!Array.isArray(rows)) throw Error("Invalid outbox checkpoint rows");
  return rows.map((row) => ({
    id: row.id,
    event_key: row.eventKey,
    transport: row.transport,
    target_ref: row.targetRef,
    route_payload: row.route,
  }));
}
function validateMapping(
  m: FixtureManifest,
  value: EventMapping,
): EventMapping {
  const r = object(value);
  if (
    Object.keys(r).sort().join() !==
      "desiredStateVersion,eventKey,recipients,route" ||
    r.desiredStateVersion !== m.expectedDesiredStateVersion ||
    !Array.isArray(r.recipients)
  ) throw Error("Invalid event mapping");
  const rows = r.recipients.map((raw) => {
    const recipient = object(raw);
    if (Object.keys(recipient).sort().join() !== "id,targetRef,transport") {
      throw Error("Invalid event recipient");
    }
    return {
      id: recipient.id,
      event_key: r.eventKey,
      transport: recipient.transport,
      target_ref: recipient.targetRef,
      route_payload: r.route,
    };
  });
  const validated = validateDispatchRows(m, rows);
  if (!validated[0].route.resourceId) throw Error("Missing event identity");
  return value;
}
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
      Object.keys(route).length !== (route.resourceId ? 6 : 5) ||
      route.kind !== "device.state.changed" ||
      !sameId(route.familyId, m.familyId) ||
      !sameId(route.childId, m.childId) ||
      !sameId(route.deviceId, m.deviceId!) ||
      Object.keys(target).length !== 1
    ) throw Error("Foreign or malformed outbox row");
    seen.add(rowId);
    let targetRef: Record<string, string>;
    if (r.transport === "fcm" && sameId(target.deviceId, deviceId)) {
      targetRef = { deviceId };
    } else if (
      r.transport === "web_push" && typeof target.subscriptionId === "string" &&
      m.subscriptionIds.some((subscription) =>
        sameId(target.subscriptionId, subscription)
      )
    ) targetRef = { subscriptionId: id(target.subscriptionId) };
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
  if (
    new Set(rows.map((row) => row.route.resourceId?.toLowerCase() ?? "legacy"))
      .size !== 1
  ) throw Error("Inconsistent event identity");
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
export async function dispatchCheckpointed(
  manifest: FixtureManifest,
  rows: unknown,
  workerKey: string,
  deps: {
    load(): Promise<unknown>;
    save(mappings: EventMapping[]): Promise<void>;
    send(id: string, key: string): Promise<DeliveryOutcome>;
  },
): Promise<
  {
    outcomes: Awaited<ReturnType<typeof dispatchRows>>;
    eventMapping: EventMapping | null;
  }
> {
  const m = validateManifest(manifest);
  const validated = validateDispatchRows(m, rows);
  let mappings = validateHistory(m, await deps.load());
  if (validated[0].route.resourceId) {
    mappings = checkpointEvent(m, validated, mappings);
    await deps.save(mappings);
  } else if (
    mappings.some((record) =>
      record.desiredStateVersion === m.expectedDesiredStateVersion
    )
  ) {
    throw Error("Legacy event conflicts with identified checkpoint");
  }
  return {
    outcomes: await dispatchRows(m, rows, workerKey, deps.send),
    eventMapping:
      mappings.find((record) =>
        record.desiredStateVersion === m.expectedDesiredStateVersion
      ) ?? null,
  };
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
export function discoverCleanupFixture(
  manifest: FixtureManifest,
  input: unknown,
): FixtureManifest {
  const m = validateManifest(manifest), e = object(input);
  if (
    e.projectRef !== projectRef || e.runId !== m.runId ||
    e.familyId !== m.familyId || e.childId !== m.childId ||
    e.complete !== true || !Array.isArray(e.devices) || e.devices.length > 1
  ) throw Error("Complete trusted fixture binding discovery required");
  if (e.devices.length === 0) {
    if (m.deviceId || m.childAuthUserId) {
      throw Error(
        "Recorded binding requires checkpointed discovery during recovery",
      );
    }
    return m;
  }
  const binding = object(e.devices[0]),
    deviceId = id(binding.deviceId),
    authUserId = id(binding.authUserId);
  if (
    (m.deviceId && m.deviceId !== deviceId) ||
    (m.childAuthUserId && m.childAuthUserId !== authUserId)
  ) throw Error("Foreign cleanup binding");
  return { ...m, deviceId, childAuthUserId: authUserId };
}
export async function rollbackPreparation(
  journal: PreparationJournal,
  deps: {
    family(id: string): Promise<void>;
    parent(id: string): Promise<void>;
  },
) {
  if (journal.projectRef !== projectRef) throw Error("Wrong recovery project");
  id(journal.runId);
  const failed: string[] = [];
  if (!journal.parentUserId || !journal.familyId) {
    return { complete: false, failed: ["exact-run-family-recovery"], journal };
  }
  try {
    await deps.family(id(journal.familyId));
  } catch {
    failed.push("family");
  }
  // Retain parent/idempotency mapping if exact-family removal is not confirmed.
  if (!failed.length) {
    try {
      await deps.parent(id(journal.parentUserId));
    } catch {
      failed.push("parent-auth");
    }
  }
  return { complete: failed.length === 0, failed, journal };
}
export async function cleanupPhase<T>(
  manifest: FixtureManifest,
  phase: unknown,
  evidence: unknown,
  deps: { revoke(): Promise<void>; finalize(): Promise<T> },
): Promise<T | { revoked: true; complete: false }> {
  const m = validateManifest(manifest);
  if (phase === "revoke") {
    await deps.revoke();
    return { revoked: true, complete: false };
  }
  if (phase !== "finalize") {
    throw Error("Choose cleanup revoke or finalize phase");
  }
  if (m.deviceId) {
    const e = object(evidence),
      sync = object(e.sync),
      registration = object(e.registration);
    if (
      e.deviceId !== m.deviceId || sync.status !== 403 ||
      sync.code !== "DEVICE_REVOKED" || registration.status !== 403 ||
      registration.code !== "DEVICE_REVOKED"
    ) {
      throw Error(
        "Both signed revoked-device denials required before deleting identity",
      );
    }
  }
  return await deps.finalize();
}
export function assertRestrictedAcl(input: unknown) {
  const acl = object(input);
  if (
    typeof acl.current !== "string" || acl.owner !== acl.current ||
    acl.protected !== true || !Array.isArray(acl.readers) ||
    !acl.readers.includes(acl.current) ||
    acl.readers.some((reader) =>
      reader !== acl.current && reader !== "S-1-5-18"
    )
  ) throw Error("Sensitive file ACL must allow only operator and SYSTEM");
}
export async function finalizeCleanup(
  manifest: FixtureManifest,
  stages: { name: string; run(): Promise<void> }[],
) {
  const names = [
    "browser",
    "private-rows",
    "family",
    "child-auth",
    "parent-auth",
  ];
  if (
    stages.length !== names.length ||
    stages.some((stage, index) => stage.name !== names[index])
  ) throw Error("Incomplete cleanup stages");
  let browser = false, privateRows = false, domainRemoved = false;
  return await cleanupStages(
    manifest,
    stages.map((stage) => ({
      name: stage.name,
      run: async () => {
        if (stage.name === "family" && (!browser || !privateRows)) {
          throw Error(
            "Retain identities until browser and private rows are cleaned",
          );
        }
        if (
          (stage.name === "child-auth" || stage.name === "parent-auth") &&
          !domainRemoved
        ) throw Error("Retain Auth recovery until domain removal succeeds");
        await stage.run();
        if (stage.name === "browser") browser = true;
        if (stage.name === "private-rows") privateRows = true;
        if (stage.name === "family") domainRemoved = true;
      },
    })),
  );
}
export type Receipt = { route: NotificationRouteRefV1; receivedAt: string };
export function correlateReceipts(
  manifest: FixtureManifest,
  eventKey: string,
  startedAt: string,
  input: unknown,
  baselineCount: number,
  mapping?: EventMapping,
): Receipt[] {
  const m = validateManifest(manifest), start = Date.parse(startedAt);
  if (
    !m.deviceId ||
    eventKey !==
      `desired-state:${m.deviceId}:${m.expectedDesiredStateVersion}` ||
    !Number.isFinite(start) || !Array.isArray(input) ||
    !Number.isSafeInteger(baselineCount) || baselineCount < 0 ||
    baselineCount > input.length
  ) throw Error("Invalid serialized observation boundary");
  if (!mapping) return [];
  try {
    validateMapping(m, mapping);
  } catch {
    return [];
  }
  return input.slice(baselineCount).flatMap((raw) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return [];
    const value = raw as Record<string, unknown>,
      route = notificationRoute(value.route);
    const time = typeof value.receivedAt === "number"
      ? value.receivedAt
      : typeof value.receivedAt === "string"
      ? Date.parse(value.receivedAt)
      : NaN;
    if (
      Object.keys(value).some((key) =>
        key !== "route" && key !== "receivedAt"
      ) || !route || Object.keys(route).length !== 6 ||
      route.kind !== "device.state.changed" ||
      !sameId(route.familyId, m.familyId) ||
      !sameId(route.childId, m.childId) ||
      !sameId(route.deviceId, m.deviceId!) ||
      canonical(route) !== canonical(mapping.route) ||
      !Number.isFinite(time) || time < start || time > start + 120000
    ) return [];
    return [{ route, receivedAt: new Date(time).toISOString() }];
  });
}
export function classifyDelivery(
  provider: DeliveryOutcome,
  matchingReceipts: Receipt[],
  elapsedSeconds: number,
): "received" | "unverified" | "provider_failure" {
  if (!Number.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
    throw Error("Invalid observation elapsed time");
  }
  if (provider.status === "retry" || provider.status === "dead_letter") {
    return "provider_failure";
  }
  if (provider.status !== "sent") {
    return "unverified";
  }
  return matchingReceipts.length ? "received" : "unverified";
}
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
      `$env:PSModulePath=Join-Path $PSHOME 'Modules'; $ErrorActionPreference='Stop'; $p=$args[0]; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; $acl=New-Object Security.AccessControl.DirectorySecurity; $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false); foreach($s in @($sid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}; Set-Acl -LiteralPath $p -AclObject $acl; $check=Get-Acl -LiteralPath $p; if(!$check.AreAccessRulesProtected){throw 'Protection failed'}; foreach($r in $check.Access){$actual=$r.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value; if($actual -ne $sid.Value -and $actual -ne 'S-1-5-18'){throw 'Unexpected access'}}`;
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
export async function protectFile(file: URL) {
  try {
    if ((await Deno.lstat(file)).isSymlink) throw Error("Unsafe operator file");
  } catch (e) {
    if (!(e instanceof Deno.errors.NotFound)) throw e;
    const created = await Deno.open(file, {
      createNew: true,
      write: true,
      mode: 0o600,
    });
    created.close();
  }
  if (Deno.build.os !== "windows") {
    await Deno.chmod(file, 0o600);
    return;
  }
  const script =
    `$env:PSModulePath=Join-Path $PSHOME 'Modules'; $ErrorActionPreference='Stop'; $p=$args[0]; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; $acl=[Security.AccessControl.FileSecurity]::new(); $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false); foreach($s in @($sid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'FullControl','Allow'))}; Set-Acl -LiteralPath $p -AclObject $acl; $check=Get-Acl -LiteralPath $p; @{owner=$check.Owner.Translate([Security.Principal.SecurityIdentifier]).Value;current=$sid.Value;protected=$check.AreAccessRulesProtected;readers=@($check.Access|ForEach-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value})}|ConvertTo-Json -Compress`;
  const scriptPath = await Deno.makeTempFile({ suffix: ".ps1" });
  try {
    // Owner is returned as an NTAccount string by Get-Acl, so request SID conversion explicitly.
    await Deno.writeTextFile(
      scriptPath,
      script.replace(
        "$check.Owner.Translate([Security.Principal.SecurityIdentifier]).Value",
        "$check.GetOwner([Security.Principal.SecurityIdentifier]).Value",
      ),
    );
    const result = await new Deno.Command("powershell.exe", {
      args: [
        "-NoProfile",
        "-NonInteractive",
        "-File",
        scriptPath,
        await Deno.realPath(file),
      ],
      stdout: "piped",
      stderr: "null",
    }).output();
    if (!result.success) throw Error("Sensitive file protection failed");
    assertRestrictedAcl(JSON.parse(new TextDecoder().decode(result.stdout)));
  } finally {
    await Deno.remove(scriptPath);
  }
}
async function save(name: string, value: unknown) {
  const file = localFile(name);
  await protectFile(file); // Existing explicit ACEs must be removed before writing secrets.
  await Deno.writeTextFile(file, JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  if (Deno.build.os !== "windows") await Deno.chmod(file, 0o600);
}
async function load(name: string) {
  if ((await Deno.lstat(localFile(name))).isSymlink) {
    throw Error("Unsafe operator file");
  }
  await protectFile(localFile(name));
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
    const { outcomes, eventMapping } = await dispatchCheckpointed(
      m,
      input.rows,
      typeof input.workerKey === "string" ? input.workerKey : "",
      {
        load: async () => {
          try {
            return await load("event-mappings.json");
          } catch (error) {
            if (!(error instanceof Deno.errors.NotFound)) throw error;
            return [];
          }
        },
        save: (mappings) => save("event-mappings.json", mappings),
        send: async (outboxId, key) => {
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
      },
    );
    await save("dispatch.json", {
      at: new Date().toISOString(),
      eventVersion: m.expectedDesiredStateVersion,
      eventMapping,
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
          const result = await rollbackPreparation(journal, {
            family: async (familyId) => {
              ok(
                (await admin.from("families").delete().eq("id", familyId))
                  .error,
              );
            },
            parent: async (userId) => {
              ok((await admin.auth.admin.deleteUser(userId)).error);
            },
          });
          await save("cleanup.json", result);
          if (!result.complete) {
            throw Error(
              "Rollback incomplete; trusted exact parent/run family lookup required",
            );
          }
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
    let m = validateManifest(await load("manifest.json"));
    if (command === "cleanup") {
      m = discoverCleanupFixture(m, input.fixtureDiscovery);
      await save("manifest.json", m); // Checkpoint every discovered binding before domain deletion.
      await save("discovery.json", input.fixtureDiscovery);
    }
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
    const stages = [
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
    ];
    const result = await cleanupPhase(
      m,
      input.phase ?? "revoke",
      input.revocationEvidence,
      {
        revoke: stages.find((stage) => stage.name === "revoke")!.run,
        finalize: () =>
          finalizeCleanup(m, stages.filter((stage) => stage.name !== "revoke")),
      },
    );
    if ("revoked" in result) {
      await save("revocation.json", {
        deviceId: m.deviceId,
        revoked: true,
        complete: false,
      });
      console.log(
        JSON.stringify({
          command,
          phase: "revoke",
          revoked: true,
          complete: false,
          next:
            "Verify signed sync and registration both return 403 DEVICE_REVOKED before finalization",
        }),
      );
      return;
    }
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
