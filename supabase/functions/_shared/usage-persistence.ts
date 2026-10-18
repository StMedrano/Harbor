import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  ClearUsageV1,
  UsageCheckpointReplyV1,
  UsageReadReplyV1,
  UsageReportV1,
  UsageWriteReplyV1,
} from "../../../packages/contracts/src/v1/usage.ts";
type Sql = ReturnType<typeof getPrivateSql>;
function sequence(value: unknown): number {
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0) {
    throw Error("Invalid usage checkpoint response");
  }
  return n;
}
function receipt(row: Record<string, unknown>): UsageWriteReplyV1 {
  if (row.confirmed !== true) throw Error("Usage write not confirmed");
  return {
    confirmed: true,
    sequence: sequence(row.sequence),
    receivedAt: new Date(row.received_at as string).toISOString(),
  };
}
export function createUsagePersistence(sql: Sql) {
  return {
    async writeUsage(
      ctx: DeviceProofContext,
      report: UsageReportV1,
      hash: string,
    ): Promise<UsageWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_write_device_usage(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${
          sql.json(report as unknown as postgres.JSONValue)
        }::jsonb,${hash}::text)`;
      if (!rows[0]) throw Error("Usage write not confirmed");
      return receipt(rows[0]);
    },
    async clearUsage(
      ctx: DeviceProofContext,
      input: ClearUsageV1,
      hash: string,
    ): Promise<UsageWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_clear_device_usage(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${input.epochId}::uuid,${input.sequence}::bigint,${hash}::text)`;
      if (!rows[0]) throw Error("Usage clear not confirmed");
      return receipt(rows[0]);
    },
    async readUsage(
      parent: ParentContext,
      deviceId: string,
    ): Promise<UsageReadReplyV1> {
      if (!parent.sessionId) {
        throw new HarborAuthError(
          "AUTH_REQUIRED",
          401,
          "Current parent session required",
        );
      }
      const rows =
        await sql`select * from private.harbor_get_device_usage(${parent.userId}::uuid,${parent.sessionId}::uuid,${deviceId}::uuid)`;
      const row = rows[0];
      if (!row || !["none", "expired", "available"].includes(row.state)) {
        throw Error("Usage read not confirmed");
      }
      return {
        state: row.state,
        report: row.report,
        receivedAt: row.received_at
          ? new Date(row.received_at).toISOString()
          : null,
      };
    },
    async readUsageCheckpoint(
      ctx: DeviceProofContext,
    ): Promise<UsageCheckpointReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_usage_checkpoint(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      if (!rows[0]) throw Error("Usage checkpoint not confirmed");
      return {
        sequence: sequence(rows[0].sequence),
        epochId: rows[0].epoch_id ?? null,
      };
    },
  };
}
export const writeUsage = (
  ctx: DeviceProofContext,
  report: UsageReportV1,
  hash: string,
) => createUsagePersistence(getPrivateSql()).writeUsage(ctx, report, hash);
export const clearUsage = (
  ctx: DeviceProofContext,
  input: ClearUsageV1,
  hash: string,
) => createUsagePersistence(getPrivateSql()).clearUsage(ctx, input, hash);
export const readUsage = (parent: ParentContext, deviceId: string) =>
  createUsagePersistence(getPrivateSql()).readUsage(parent, deviceId);
export const readUsageCheckpoint = (ctx: DeviceProofContext) =>
  createUsagePersistence(getPrivateSql()).readUsageCheckpoint(ctx);
