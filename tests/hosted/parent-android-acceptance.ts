// Opt-in development acceptance helpers. Imports perform no hosted calls.
// Structural validation is not cleanup authority: use trusted exact-run SQL/Auth
// discovery before any destructive operation through the existing operator.
import {
  correlateReceipts,
  type EventMapping,
  type FixtureManifest,
  type OutboxRow,
  protectDirectory,
  protectFile,
  validateDispatchRows,
  validateManifest,
} from "./notification-acceptance.ts";
import { notificationRoute } from "../../supabase/functions/_shared/notification.ts";
import type { DeliveryOutcome } from "../../supabase/functions/_shared/outbox-dispatch.ts";

export type ParentFixtureManifest = FixtureManifest & {
  parentSessionId: string;
  parentRegistrationId: string;
  parentInstallationId: string;
};
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw Error("Invalid parent operator input");
  }
  return value as Record<string, unknown>;
}
function id(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  ) throw Error("Invalid parent fixture binding");
  return value.toLowerCase();
}
function canonical(input: unknown): string {
  const value = object(input);
  return JSON.stringify(
    Object.fromEntries(
      Object.keys(value).sort().map(
        (key) => [
          key,
          typeof value[key] === "string" && key !== "kind"
            ? (value[key] as string).toLowerCase()
            : value[key],
        ],
      ),
    ),
  );
}
export function validateParentManifest(value: unknown): ParentFixtureManifest {
  const raw = object(value), base = validateManifest(raw);
  if (!base.deviceId || !base.childAuthUserId) {
    throw Error("Complete claimed fixture required");
  }
  return {
    ...base,
    parentSessionId: id(raw.parentSessionId),
    parentRegistrationId: id(raw.parentRegistrationId),
    parentInstallationId: id(raw.parentInstallationId),
  };
}
export function validateParentDispatchRows(
  value: unknown,
  input: unknown,
): OutboxRow[] {
  const manifest = validateParentManifest(value);
  if (!Array.isArray(input)) throw Error("Invalid parent batch");
  const parentRows = input.filter((raw) =>
    "parentFcmRegistrationId" in object(object(raw).target_ref)
  );
  if (parentRows.length !== 1) {
    throw Error("One exact parent recipient required");
  }
  const legacy = validateDispatchRows(
    manifest,
    input.filter((raw) => !parentRows.includes(raw)),
  );
  const raw = object(parentRows[0]),
    target = object(raw.target_ref),
    route = notificationRoute(raw.route_payload);
  const rowId = id(raw.id);
  if (
    raw.transport !== "fcm" ||
    Object.keys(target).sort().join(",") !== "parentFcmRegistrationId,userId" ||
    id(target.parentFcmRegistrationId) !== manifest.parentRegistrationId ||
    id(target.userId) !== manifest.parentUserId ||
    legacy.some((row) => row.id === rowId) ||
    raw.event_key !== legacy[0].eventKey || !route || !route.resourceId ||
    canonical(route) !== canonical(legacy[0].route)
  ) throw Error("Foreign or inconsistent parent recipient");
  return [...legacy, {
    id: rowId,
    eventKey: legacy[0].eventKey,
    transport: "fcm",
    targetRef: {
      parentFcmRegistrationId: manifest.parentRegistrationId,
      userId: manifest.parentUserId,
    },
    route,
  }];
}
export async function dispatchParentRows(
  value: unknown,
  input: unknown,
  workerKey: string,
  deps: {
    checkpoint(mapping: EventMapping): Promise<void>;
    send(id: string, key: string): Promise<DeliveryOutcome>;
  },
) {
  const manifest = validateParentManifest(value),
    rows = validateParentDispatchRows(manifest, input);
  if (!workerKey?.trim()) throw Error("Missing secure worker input");
  const mapping: EventMapping = {
    eventKey: rows[0].eventKey,
    desiredStateVersion: manifest.expectedDesiredStateVersion,
    route: rows[0].route,
    recipients: rows.map(({ id, transport, targetRef }) => ({
      id,
      transport,
      targetRef,
    })),
  };
  await deps.checkpoint(mapping); // Persist exact identities before any provider attempt.
  const outcomes = [];
  for (const row of rows) {
    try {
      const outcome = await deps.send(row.id, workerKey);
      if (!["sent", "retry", "dead_letter", "no_op"].includes(outcome.status)) {
        throw Error("Invalid parent worker outcome");
      }
      outcomes.push({ id: row.id, status: outcome.status });
    } catch {
      outcomes.push({ id: row.id, status: "unverified" as const });
    }
  }
  return { mapping, outcomes };
}
export function correlateParentReceipts(
  value: unknown,
  eventKey: string,
  startedAt: string,
  input: unknown,
  baselineCount: number,
  mapping: EventMapping,
) {
  const manifest = validateParentManifest(value);
  const rows = validateParentDispatchRows(
    manifest,
    mapping.recipients.map((row) => ({
      id: row.id,
      event_key: mapping.eventKey,
      transport: row.transport,
      target_ref: row.targetRef,
      route_payload: mapping.route,
    })),
  );
  if (
    !Array.isArray(input) || !Number.isSafeInteger(baselineCount) ||
    baselineCount < 0 || baselineCount > input.length
  ) throw Error("Invalid parent observation boundary");
  const receipts = input.slice(baselineCount).flatMap((raw) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return [];
    const item = raw as Record<string, unknown>;
    if (
      Object.keys(item).sort().join(",") !==
        "receivedAt,registrationId,route" ||
      typeof item.registrationId !== "string" ||
      item.registrationId.toLowerCase() !== manifest.parentRegistrationId
    ) return [];
    return [{ route: item.route, receivedAt: item.receivedAt }];
  });
  // Reuse the existing exact-event/time-window proof for the shared V1 route.
  return correlateReceipts(manifest, eventKey, startedAt, receipts, 0, {
    ...mapping,
    recipients: rows.filter((row) => !row.targetRef.parentFcmRegistrationId)
      .map(({ id, transport, targetRef }) => ({ id, transport, targetRef })),
  });
}
export function parentJournal(value: unknown, input: unknown) {
  const manifest = validateParentManifest(value);
  const raw = object(input);
  if (
    !Array.isArray(raw.recipients) ||
    raw.desiredStateVersion !== manifest.expectedDesiredStateVersion
  ) throw Error("Invalid parent event checkpoint");
  const rows = validateParentDispatchRows(
    manifest,
    raw.recipients.map((item) => ({
      id: object(item).id,
      event_key: raw.eventKey,
      transport: object(item).transport,
      target_ref: object(item).targetRef,
      route_payload: raw.route,
    })),
  );
  const mapping: EventMapping = {
    eventKey: rows[0].eventKey,
    desiredStateVersion: manifest.expectedDesiredStateVersion,
    route: rows[0].route,
    recipients: rows.map(({ id, transport, targetRef }) => ({
      id,
      transport,
      targetRef,
    })),
  };
  return { manifest, mapping };
}
export async function saveParentJournal(value: unknown, mapping: EventMapping) {
  const journal = parentJournal(value, mapping);
  const root = new URL(
    "../../tools/notification-acceptance/operator/",
    import.meta.url,
  );
  const file = new URL("parent-android-journal.json", root);
  await protectDirectory(root);
  try {
    const info = await Deno.lstat(file);
    if (info.isSymlink || !info.isFile) {
      throw Error("Unsafe parent journal file");
    }
  } catch (error) {
    if (!(error instanceof Deno.errors.NotFound)) throw error;
  }
  await Deno.writeTextFile(file, JSON.stringify(journal), {
    mode: 0o600,
  });
  await protectFile(file);
}
