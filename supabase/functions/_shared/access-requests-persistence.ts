import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  AccessDecisionReplyV1,
  AccessDecisionV1,
  AccessRequestItemV1,
  AccessRequestReplyV1,
  AccessRequestsReplyV1,
  AccessRequestV1,
} from "../../../packages/contracts/src/v1/access-requests.ts";
type Sql = ReturnType<typeof getPrivateSql>;
/** Whole-second ISO instant, as the contract requires. */
const seconds = (value: unknown) => new Date(value as string).toISOString().replace(/\.\d{3}Z$/, "Z");
function sessionOf(parent: ParentContext): string {
  if (!parent.sessionId) throw new HarborAuthError("AUTH_REQUIRED", 401, "Current parent session required");
  return parent.sessionId;
}
export function createAccessPersistence(sql: Sql) {
  return {
    async requestAccess(ctx: DeviceProofContext, r: AccessRequestV1): Promise<AccessRequestReplyV1> {
      const rows =
        await sql`select * from private.harbor_request_device_access(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid,${r.requestId}::uuid,${r.reason}::text,${r.packageName}::text,${r.appLabel}::text)`;
      const row = rows[0];
      if (!row) throw Error("Access request not confirmed");
      return { requestId: row.request_id, status: row.status, requestedAt: seconds(row.requested_at) };
    },
    async readRequests(parent: ParentContext, deviceId: string): Promise<AccessRequestsReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_access_requests(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${deviceId}::uuid)`;
      return {
        requests: rows.map((row): AccessRequestItemV1 => ({
          requestId: row.request_id,
          reason: row.reason,
          packageName: row.package_name,
          appLabel: row.app_label,
          requestedAt: seconds(row.requested_at),
          status: row.status,
          decidedAt: row.decided_at ? seconds(row.decided_at) : null,
        })),
      };
    },
    async decide(parent: ParentContext, d: AccessDecisionV1): Promise<AccessDecisionReplyV1> {
      const rows =
        await sql`select * from private.harbor_decide_access_request(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${d.requestId}::uuid,${d.decision}::text)`;
      const row = rows[0];
      if (!row || (row.status !== "approved" && row.status !== "denied")) throw Error("Access decision not confirmed");
      return { requestId: row.request_id, status: row.status };
    },
  };
}
const make = () => createAccessPersistence(getPrivateSql());
export const requestAccess = (ctx: DeviceProofContext, r: AccessRequestV1) => make().requestAccess(ctx, r);
export const readRequests = (parent: ParentContext, deviceId: string) => make().readRequests(parent, deviceId);
export const decide = (parent: ParentContext, d: AccessDecisionV1) => make().decide(parent, d);
