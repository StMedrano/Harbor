import type { ChildV1 } from "../../../packages/contracts/src/v1/family.ts";
import { getPrivateSql } from "./clients.ts";
export type ChildCreationInput = {
  userId: string;
  familyId: string;
  displayName: string;
  idempotencyKey: string;
  payloadHash: string;
};
export async function createChildAtomic(
  input: ChildCreationInput,
): Promise<ChildV1> {
  const sql = getPrivateSql();
  const rows = await sql<
    Array<
      {
        id: string;
        family_id: string;
        display_name: string;
        created_at: Date;
        updated_at: Date;
      }
    >
  >`
    select * from private.harbor_create_child(${input.userId}::uuid,${input.familyId}::uuid,${input.displayName},${input.idempotencyKey},${input.payloadHash})`;
  if (rows.length !== 1) {
    throw new Error("Child creation returned no canonical result");
  }
  const row = rows[0];
  return {
    version: 1,
    id: row.id,
    familyId: row.family_id,
    displayName: row.display_name,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
  };
}
