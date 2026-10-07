import type { RegisterParentFcmReplyV1 } from "../../../packages/contracts/src/v1/parent-notifications.ts";
import { getPrivateSql } from "./clients.ts";
export type ParentFcmRegistrationInput = {
  userId: string;
  sessionId: string;
  clientInstallationId: string;
  token: string;
};
export type ParentFcmRemovalInput = {
  userId: string;
  sessionId: string;
  clientInstallationId: string;
};
export function parentInstallation(value: unknown): string | null {
  if (
    typeof value !== "string" || /[\u0000-\u001f\u007f-\u009f]/u.test(value)
  ) return null;
  const id = value.trim();
  return id && Array.from(id).length <= 200 ? id : null;
}
export async function registerParentFcmAtomic(
  input: ParentFcmRegistrationInput,
): Promise<RegisterParentFcmReplyV1> {
  const rows = await getPrivateSql()<
    Array<{ registration_id: string; active: boolean }>
  >`select * from private.harbor_register_parent_fcm(${input.userId}::uuid,${input.sessionId}::uuid,${input.clientInstallationId},${input.token})`;
  if (rows.length !== 1 || rows[0].active !== true) {
    throw new Error("Parent registration was not confirmed");
  }
  return { registrationId: rows[0].registration_id, active: true };
}
export async function removeParentFcmAtomic(
  input: ParentFcmRemovalInput,
): Promise<void> {
  await getPrivateSql()`select private.harbor_remove_parent_fcm(${input.userId}::uuid,${input.sessionId}::uuid,${input.clientInstallationId})`;
}
