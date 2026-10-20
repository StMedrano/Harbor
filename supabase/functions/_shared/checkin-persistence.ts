import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  CheckInEntryV1,
  CheckInReadReplyV1,
  CheckInReplyInputV1,
  CheckInReplyReceiptV1,
  CheckInRequestReplyV1,
  CheckInStateReplyV1,
} from "../../../packages/contracts/src/v1/checkin.ts";
type Sql = ReturnType<typeof getPrivateSql>;
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
export function createCheckInPersistence(sql: Sql) {
  return {
    async requestCheckIn(
      parent: ParentContext,
      deviceId: string,
    ): Promise<CheckInRequestReplyV1> {
      const rows =
        await sql`select * from private.harbor_request_device_checkin(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${deviceId}::uuid)`;
      const row = rows[0];
      if (!row) throw Error("Check-in request not confirmed");
      return {
        requestId: row.request_id,
        requestedAt: seconds(row.requested_at),
        expiresAt: seconds(row.expires_at),
        created: row.created === true,
      };
    },
    async readCheckIns(
      parent: ParentContext,
      deviceId: string,
    ): Promise<CheckInReadReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_device_checkins(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${deviceId}::uuid)`;
      if (!rows[0]) throw Error("Check-in read not confirmed");
      return { checkins: (rows[0].checkins ?? []) as CheckInEntryV1[] };
    },
    async readChildState(ctx: DeviceProofContext): Promise<CheckInStateReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_checkin_state(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      if (!rows[0]) throw Error("Check-in state not confirmed");
      return {
        pending: rows[0].pending ?? null,
        recent: rows[0].recent ?? [],
      };
    },
    async replyCheckIn(
      ctx: DeviceProofContext,
      reply: CheckInReplyInputV1,
      hash: string,
    ): Promise<CheckInReplyReceiptV1> {
      const rows =
        await sql`select * from private.harbor_reply_device_checkin(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${
          sql.json(reply as unknown as postgres.JSONValue)
        }::jsonb,${hash}::text)`;
      if (!rows[0] || rows[0].confirmed !== true) {
        throw Error("Check-in reply not confirmed");
      }
      return { confirmed: true, receivedAt: seconds(rows[0].received_at) };
    },
  };
}
export const requestCheckIn = (parent: ParentContext, deviceId: string) =>
  createCheckInPersistence(getPrivateSql()).requestCheckIn(parent, deviceId);
export const readCheckIns = (parent: ParentContext, deviceId: string) =>
  createCheckInPersistence(getPrivateSql()).readCheckIns(parent, deviceId);
export const readChildState = (ctx: DeviceProofContext) =>
  createCheckInPersistence(getPrivateSql()).readChildState(ctx);
export const replyCheckIn = (
  ctx: DeviceProofContext,
  reply: CheckInReplyInputV1,
  hash: string,
) => createCheckInPersistence(getPrivateSql()).replyCheckIn(ctx, reply, hash);
