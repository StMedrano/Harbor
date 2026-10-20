import postgres from "npm:postgres@3.4.7";
import type { DeviceProofContext } from "./device-proof.ts";
import type { ParentContext } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
import type {
  FamilyMapPersonV1,
  FamilyMapReplyV1,
  FamilyMapSettingsReplyV1,
  ParentLocationReceiptV1,
  ParentLocationReportV1,
} from "../../../packages/contracts/src/v1/familymap.ts";
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
export function createFamilyMapPersistence(sql: Sql) {
  const settings = async (
    parent: ParentContext,
    familyId: string,
  ): Promise<FamilyMapSettingsReplyV1> => {
    const rows =
      await sql`select * from private.harbor_get_family_map_settings(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${familyId}::uuid)`;
    const row = rows[0];
    if (!row) throw Error("Family map settings not confirmed");
    return {
      childrenCanView: row.children_can_view === true,
      mySharing: row.my_label
        ? { label: row.my_label, lastAt: seconds(row.my_last_at) }
        : null,
    };
  };
  return {
    async readFamilyMap(ctx: DeviceProofContext): Promise<FamilyMapReplyV1> {
      const rows =
        await sql`select * from private.harbor_get_family_map(${ctx.deviceId}::uuid,${ctx.familyId}::uuid,${ctx.childId}::uuid,${ctx.authUserId}::uuid)`;
      const row = rows[0];
      if (!row) throw Error("Family map not confirmed");
      return {
        enabled: row.enabled === true,
        people: (row.people ?? []) as FamilyMapPersonV1[],
      };
    },
    async reportParentLocation(
      parent: ParentContext,
      report: ParentLocationReportV1,
    ): Promise<ParentLocationReceiptV1> {
      const body = { label: report.label, fix: report.fix };
      const rows =
        await sql`select * from private.harbor_report_parent_location(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${report.familyId}::uuid,${
          sql.json(body as unknown as postgres.JSONValue)
        }::jsonb)`;
      if (!rows[0] || rows[0].confirmed !== true) {
        throw Error("Parent location not confirmed");
      }
      return { confirmed: true, receivedAt: seconds(rows[0].received_at) };
    },
    async stopParentLocation(
      parent: ParentContext,
      familyId: string,
    ): Promise<ParentLocationReceiptV1> {
      const rows =
        await sql`select * from private.harbor_stop_parent_location(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${familyId}::uuid)`;
      if (!rows[0] || rows[0].confirmed !== true) {
        throw Error("Stop not confirmed");
      }
      return { confirmed: true, receivedAt: seconds(rows[0].received_at) };
    },
    readSettings: settings,
    async writeSettings(
      parent: ParentContext,
      familyId: string,
      childrenCanView: boolean,
    ): Promise<FamilyMapSettingsReplyV1> {
      await sql`select * from private.harbor_set_family_map_settings(${parent.userId}::uuid,${sessionOf(parent)}::uuid,${familyId}::uuid,${childrenCanView}::boolean)`;
      return settings(parent, familyId);
    },
  };
}
const make = () => createFamilyMapPersistence(getPrivateSql());
export const readFamilyMap = (ctx: DeviceProofContext) =>
  make().readFamilyMap(ctx);
export const reportParentLocation = (
  parent: ParentContext,
  report: ParentLocationReportV1,
) => make().reportParentLocation(parent, report);
export const stopParentLocation = (parent: ParentContext, familyId: string) =>
  make().stopParentLocation(parent, familyId);
export const readSettings = (parent: ParentContext, familyId: string) =>
  make().readSettings(parent, familyId);
export const writeSettings = (
  parent: ParentContext,
  familyId: string,
  childrenCanView: boolean,
) => make().writeSettings(parent, familyId, childrenCanView);
