import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  ActivityCheckpointReplyV1,
  ActivityEventV1,
  ActivityReadReplyV1,
  ActivityReadRequestV1,
  ActivityReportV1,
  ActivityWriteReplyV1,
  ClearActivityV1,
} from "../../../packages/contracts/src/v1/activity.ts";
import {
  type ControlsV1,
  type DeviceControlsReplyV1,
  validateControls,
} from "../../../packages/contracts/src/v1/controls.ts";
type Sql = ReturnType<typeof getPrivateSql>;
function sequence(value: unknown): number {
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0) {
    throw Error("Invalid activity checkpoint response");
  }
  return n;
}
function receipt(row: Record<string, unknown>): ActivityWriteReplyV1 {
  if (row.confirmed !== true) throw Error("Activity write not confirmed");
  return {
    confirmed: true,
    sequence: sequence(row.sequence),
    receivedAt: new Date(row.received_at as string).toISOString(),
  };
}
/** Whole-second ISO instant (`YYYY-MM-DDTHH:MM:SSZ`), as the contract requires. */
const seconds = (value: unknown) =>
  new Date(value as string).toISOString().replace(/\.\d{3}Z$/, "Z");
function sessionOf(parent: ParentContext): string {
  if (!parent.sessionId) {
    throw new HarborAuthError(
      "AUTH_REQUIRED",
      401,
      "Current parent session required",
    );
  }
  return parent.sessionId;
}
export function createActivityPersistence(sql: Sql) {
  return {
    async writeActivity(
      ctx: DeviceProofContext,
      report: ActivityReportV1,
      hash: string,
    ): Promise<ActivityWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_write_device_activity(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${
          sql.json(report as unknown as postgres.JSONValue)
        }::jsonb,${hash}::text)`;
      if (!rows[0]) throw Error("Activity write not confirmed");
      return receipt(rows[0]);
    },
    async clearActivity(
      ctx: DeviceProofContext,
      input: ClearActivityV1,
      hash: string,
    ): Promise<ActivityWriteReplyV1> {
      const rows =
        await sql`select * from private.harbor_clear_device_activity(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${input.epochId}::uuid,${input.sequence}::bigint,${hash}::text)`;
      if (!rows[0]) throw Error("Activity clear not confirmed");
      return receipt(rows[0]);
    },
    async readActivity(
      parent: ParentContext,
      deviceId: string,
      window: ActivityReadRequestV1,
    ): Promise<ActivityReadReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_device_activity(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${deviceId}::uuid,${window.from}::timestamptz,${window.to}::timestamptz)`;
      const row = rows[0];
      if (!row || !["none", "expired", "available"].includes(row.state)) {
        throw Error("Activity read not confirmed");
      }
      return {
        state: row.state,
        controlsOn: row.controls_on ?? null,
        policyVersion: row.policy_version === null || row.policy_version === undefined ? null : sequence(row.policy_version),
        observedAt: row.observed_at ? seconds(row.observed_at) : null,
        receivedAt: row.received_at ? seconds(row.received_at) : null,
        events: (row.events ?? []) as ActivityEventV1[],
        truncated: row.truncated === true,
      };
    },
    async readActivityCheckpoint(
      ctx: DeviceProofContext,
    ): Promise<ActivityCheckpointReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_activity_checkpoint(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      if (!rows[0]) throw Error("Activity checkpoint not confirmed");
      return {
        sequence: sequence(rows[0].sequence),
        epochId: rows[0].epoch_id ?? null,
      };
    },
    async readControls(
      parent: ParentContext,
      deviceId: string,
    ): Promise<DeviceControlsReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_device_controls(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${deviceId}::uuid)`;
      const row = rows[0];
      if (!row) throw Error("Controls read not confirmed");
      // Desired state written before controls existed may hold another shape; it is reported as "no controls set".
      let controls: ControlsV1 | null = null;
      if (row.controls !== null && row.controls !== undefined) {
        try {
          controls = validateControls(row.controls);
        } catch {
          controls = null;
        }
      }
      return {
        controls,
        desiredStateVersion: sequence(row.desired_state_version),
        acknowledgedVersion: sequence(row.acknowledged_version),
      };
    },
  };
}
const make = () => createActivityPersistence(getPrivateSql());
export const writeActivity = (
  ctx: DeviceProofContext,
  report: ActivityReportV1,
  hash: string,
) => make().writeActivity(ctx, report, hash);
export const clearActivity = (
  ctx: DeviceProofContext,
  input: ClearActivityV1,
  hash: string,
) => make().clearActivity(ctx, input, hash);
export const readActivity = (
  parent: ParentContext,
  deviceId: string,
  window: ActivityReadRequestV1,
) => make().readActivity(parent, deviceId, window);
export const readActivityCheckpoint = (ctx: DeviceProofContext) =>
  make().readActivityCheckpoint(ctx);
export const readControls = (parent: ParentContext, deviceId: string) =>
  make().readControls(parent, deviceId);
