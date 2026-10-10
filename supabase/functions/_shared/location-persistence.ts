import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  ClearLocationV1,
  LocationCheckpointReplyV1,
  LocationFixV1,
  LocationReadReplyV1,
  LocationReportV1,
  LocationWriteReplyV1,
} from "../../../packages/contracts/src/v1/location.ts";
type Sql = ReturnType<typeof getPrivateSql>;
function sequence(value: unknown): number {
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0) {
    throw Error("Invalid location checkpoint response");
  }
  return n;
}
function receipt(row: Record<string, unknown>): LocationWriteReplyV1 {
  if (row.confirmed !== true) throw Error("Location write not confirmed");
  return {
    confirmed: true,
    sequence: sequence(row.sequence),
    receivedAt: new Date(row.received_at as string).toISOString(),
  };
}
export function createLocationPersistence(sql: Sql) {
  return {
    async writeLocation(
      ctx: DeviceProofContext,
      report: LocationReportV1,
      hash: string,
    ): Promise<LocationWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_write_device_location(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${
          sql.json(report as unknown as postgres.JSONValue)
        }::jsonb,${hash}::text)`;
      if (!rows[0]) throw Error("Location write not confirmed");
      return receipt(rows[0]);
    },
    async clearLocation(
      ctx: DeviceProofContext,
      input: ClearLocationV1,
      hash: string,
    ): Promise<LocationWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_clear_device_location(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${input.epochId}::uuid,${input.sequence}::bigint,${hash}::text)`;
      if (!rows[0]) throw Error("Location clear not confirmed");
      return receipt(rows[0]);
    },
    async readLocation(
      parent: ParentContext,
      deviceId: string,
    ): Promise<LocationReadReplyV1> {
      if (!parent.sessionId) {
        throw new HarborAuthError(
          "AUTH_REQUIRED",
          401,
          "Current parent session required",
        );
      }
      const rows =
        await sql`select * from private.harbor_get_device_location(${parent.userId}::uuid,${parent.sessionId}::uuid,${deviceId}::uuid)`;
      const row = rows[0];
      if (!row || !["none", "expired", "available"].includes(row.state)) {
        throw Error("Location read not confirmed");
      }
      return {
        state: row.state,
        status: row.loc_status ?? null,
        observedAt: row.observed_at
          ? new Date(row.observed_at).toISOString()
          : null,
        receivedAt: row.received_at
          ? new Date(row.received_at).toISOString()
          : null,
        fixes: (row.fixes ?? []) as LocationFixV1[],
      };
    },
    async readLocationCheckpoint(
      ctx: DeviceProofContext,
    ): Promise<LocationCheckpointReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_location_checkpoint(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      if (!rows[0]) throw Error("Location checkpoint not confirmed");
      return {
        sequence: sequence(rows[0].sequence),
        epochId: rows[0].epoch_id ?? null,
      };
    },
  };
}
export const writeLocation = (
  ctx: DeviceProofContext,
  report: LocationReportV1,
  hash: string,
) =>
  createLocationPersistence(getPrivateSql()).writeLocation(ctx, report, hash);
export const clearLocation = (
  ctx: DeviceProofContext,
  input: ClearLocationV1,
  hash: string,
) => createLocationPersistence(getPrivateSql()).clearLocation(ctx, input, hash);
export const readLocation = (parent: ParentContext, deviceId: string) =>
  createLocationPersistence(getPrivateSql()).readLocation(parent, deviceId);
export const readLocationCheckpoint = (ctx: DeviceProofContext) =>
  createLocationPersistence(getPrivateSql()).readLocationCheckpoint(ctx);
