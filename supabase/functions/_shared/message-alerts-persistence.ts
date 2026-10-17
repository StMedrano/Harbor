import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  AlertCheckpointReplyV1,
  AlertReadReplyV1,
  AlertReadRequestV1,
  AlertReportV1,
  AlertV1,
  AlertWriteReplyV1,
  ClearAlertsV1,
} from "../../../packages/contracts/src/v1/alerts.ts";
type Sql = ReturnType<typeof getPrivateSql>;
function sequence(value: unknown): number {
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0) {
    throw Error("Invalid alert checkpoint response");
  }
  return n;
}
function receipt(row: Record<string, unknown>): AlertWriteReplyV1 {
  if (row.confirmed !== true) throw Error("Alert write not confirmed");
  return {
    confirmed: true,
    sequence: sequence(row.sequence),
    receivedAt: new Date(row.received_at as string).toISOString(),
  };
}
/** Whole-second ISO instant (`YYYY-MM-DDTHH:MM:SSZ`), as the contract requires. */
const seconds = (value: unknown) =>
  new Date(value as string).toISOString().replace(/\.\d{3}Z$/, "Z");
export function createAlertPersistence(sql: Sql) {
  return {
    async writeAlerts(
      ctx: DeviceProofContext,
      report: AlertReportV1,
      hash: string,
    ): Promise<AlertWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_write_device_alerts(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${
          sql.json(report as unknown as postgres.JSONValue)
        }::jsonb,${hash}::text)`;
      if (!rows[0]) throw Error("Alert write not confirmed");
      return receipt(rows[0]);
    },
    async clearAlerts(
      ctx: DeviceProofContext,
      input: ClearAlertsV1,
      hash: string,
    ): Promise<AlertWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_clear_device_alerts(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${input.epochId}::uuid,${input.sequence}::bigint,${hash}::text)`;
      if (!rows[0]) throw Error("Alert clear not confirmed");
      return receipt(rows[0]);
    },
    async readAlerts(
      parent: ParentContext,
      deviceId: string,
      page: AlertReadRequestV1,
    ): Promise<AlertReadReplyV1> {
      if (!parent.sessionId) {
        throw new HarborAuthError(
          "AUTH_REQUIRED",
          401,
          "Current parent session required",
        );
      }
      const rows =
        await sql`select * from private.harbor_get_device_alerts(${parent.userId}::uuid,${parent.sessionId}::uuid,${deviceId}::uuid,${page.before}::timestamptz,${page.limit}::integer)`;
      const row = rows[0];
      if (!row || !["none", "expired", "available"].includes(row.state)) {
        throw Error("Alert read not confirmed");
      }
      return {
        state: row.state,
        notificationAccess: row.notification_access ?? null,
        smsPermission: row.sms_permission ?? null,
        observedAt: row.observed_at ? seconds(row.observed_at) : null,
        receivedAt: row.received_at ? seconds(row.received_at) : null,
        alerts: (row.alerts ?? []) as AlertV1[],
        nextBefore: row.next_before ? seconds(row.next_before) : null,
      };
    },
    async readAlertCheckpoint(
      ctx: DeviceProofContext,
    ): Promise<AlertCheckpointReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_alert_checkpoint(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      if (!rows[0]) throw Error("Alert checkpoint not confirmed");
      return {
        sequence: sequence(rows[0].sequence),
        epochId: rows[0].epoch_id ?? null,
      };
    },
  };
}
export const writeAlerts = (
  ctx: DeviceProofContext,
  report: AlertReportV1,
  hash: string,
) => createAlertPersistence(getPrivateSql()).writeAlerts(ctx, report, hash);
export const clearAlerts = (
  ctx: DeviceProofContext,
  input: ClearAlertsV1,
  hash: string,
) => createAlertPersistence(getPrivateSql()).clearAlerts(ctx, input, hash);
export const readAlerts = (
  parent: ParentContext,
  deviceId: string,
  page: AlertReadRequestV1,
) => createAlertPersistence(getPrivateSql()).readAlerts(parent, deviceId, page);
export const readAlertCheckpoint = (ctx: DeviceProofContext) =>
  createAlertPersistence(getPrivateSql()).readAlertCheckpoint(ctx);
