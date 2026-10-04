import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.105.0";
import postgres from "npm:postgres@3.4.7";

export type StaffAuthorizationRow = { role: "support" | "admin" };
export type CreateFamilyAtomicInput = { userId: string; name: string; idempotencyKey: string };
export type CreateFamilyAtomicRow = { family_id: string; name: string; role: "owner" };
export type CreateFamilyAtomicResult = { familyId: string; name: string; role: "owner" };
export type CreateFamilyAtomicQuery = (input: CreateFamilyAtomicInput) => Promise<CreateFamilyAtomicRow[]>;

export type DevicePairingAtomicInput = { parentUserId: string; childId: string; codeDigest: string; expiresAt: string };
export type DevicePairingAtomicResult = { familyId: string; childId: string; expiresAt: string };
export type DeviceClaimAtomicInput = { authUserId: string; codeDigest: string; publicKeySpki: string; displayName: string; model: string | null; androidVersion: string | null; supervisionMode: string };
export type DeviceClaimAtomicResult = { deviceId: string; familyId: string; childId: string };
export type DeviceClaimFailureResult = { failedAttempts: number; invalidated: boolean };
export type DeviceSecurityResult = { deviceId: string; familyId: string; childId: string; authUserId: string; publicKeySpki: string; revokedAt: string | null };

export type DeviceSyncAtomicInput = { deviceId: string; acknowledgedDesiredStateVersion: number | null; appliedCommandIds: string[] };
export type DeviceSyncAtomicRow = { desired_state: Record<string, unknown>; desired_state_version: number; commands: unknown[] };
export type DeviceSyncAtomicQuery = (input: DeviceSyncAtomicInput) => Promise<DeviceSyncAtomicRow[]>;
export type DeviceSyncAtomicResult = { desiredState: Record<string, unknown>; desiredStateVersion: number; commands: unknown[] };
export type DesiredStateAtomicInput = { deviceId: string; familyId: string; actorUserId: string; desiredState: Record<string, unknown>; expectedVersion: number };
export type DesiredStateAtomicQuery = (input: DesiredStateAtomicInput) => Promise<Array<{ desired_state_version: number }>>;
export type RegisterDeviceFcmAtomicInput = { deviceId: string; token: string };
export type RegisterDeviceFcmAtomicQuery = (input: RegisterDeviceFcmAtomicInput) => Promise<Array<{ registered: boolean }>>;
export type RevokeDeviceAtomicInput = { deviceId: string; familyId: string; actorUserId: string };
export type RevokeDeviceAtomicQuery = (input: RevokeDeviceAtomicInput) => Promise<Array<{ revoked: boolean }>>;

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function resolvePublishableKey(): string {
  const direct = Deno.env.get("SUPABASE_PUBLISHABLE_KEY")?.trim() || Deno.env.get("HARBOR_SUPABASE_PUBLISHABLE_KEY")?.trim();
  if (direct) return direct;
  const keyMapRaw = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS")?.trim();
  if (keyMapRaw) {
    const keyMap = JSON.parse(keyMapRaw) as Record<string, unknown>;
    const defaultIdentifier = keyMap.default;
    if (typeof defaultIdentifier === "string" && defaultIdentifier.trim()) {
      const mapped = Deno.env.get(defaultIdentifier)?.trim();
      if (mapped) return mapped;
    }
  }
  const legacy = Deno.env.get("SUPABASE_ANON_KEY")?.trim();
  if (legacy) return legacy;
  throw new Error("No Supabase publishable key is configured");
}

export function createRequestClient(accessToken: string): SupabaseClient {
  return createClient(requiredEnv("SUPABASE_URL"), resolvePublishableKey(), {
    global: { headers: { Authorization: `Bearer ${accessToken}` } },
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });
}

let privateSql: ReturnType<typeof postgres> | undefined;
function getPrivateSql() {
  if (!privateSql) privateSql = postgres(requiredEnv("SUPABASE_DB_URL"), { prepare: false, max: 3, idle_timeout: 20 });
  return privateSql;
}

export async function queryStaffAuthorization(userId: string): Promise<StaffAuthorizationRow | null> {
  const rows = await getPrivateSql()<StaffAuthorizationRow[]>`select role from private.staff_authorizations where user_id = ${userId}::uuid limit 1`;
  return rows[0] ?? null;
}

async function queryCreateFamilyAtomic(input: CreateFamilyAtomicInput): Promise<CreateFamilyAtomicRow[]> {
  return await getPrivateSql()<CreateFamilyAtomicRow[]>`select family_id, name, role from private.create_family_atomic(${input.userId}::uuid, ${input.name}::text, ${input.idempotencyKey}::text)`;
}

export async function createFamilyAtomicWithQuery(input: CreateFamilyAtomicInput, query: CreateFamilyAtomicQuery): Promise<CreateFamilyAtomicResult> {
  const row = (await query(input))[0];
  if (!row) throw new Error("Atomic family creation returned no result");
  if (row.role !== "owner") throw new Error("Atomic family creation returned an invalid role");
  return { familyId: row.family_id, name: row.name, role: "owner" };
}

export async function createFamilyAtomic(input: CreateFamilyAtomicInput): Promise<CreateFamilyAtomicResult> {
  return await createFamilyAtomicWithQuery(input, queryCreateFamilyAtomic);
}

export async function issueDevicePairingAtomic(input: DevicePairingAtomicInput): Promise<DevicePairingAtomicResult> {
  const rows = await getPrivateSql()<Array<{ family_id: string; child_id: string; expires_at: string }>>`
    select family_id, child_id, expires_at from private.issue_device_pairing(${input.parentUserId}::uuid, ${input.childId}::uuid, ${input.codeDigest}::text, ${input.expiresAt}::timestamptz)`;
  const row = rows[0];
  if (!row) throw new Error("Device pairing returned no result");
  return { familyId: row.family_id, childId: row.child_id, expiresAt: new Date(row.expires_at).toISOString() };
}

export async function recordDeviceClaimFailure(codeDigest: string): Promise<DeviceClaimFailureResult> {
  const rows = await getPrivateSql()<Array<{ failed_attempts: number; invalidated: boolean }>>`
    select failed_attempts, invalidated from private.record_device_claim_failure(${codeDigest}::text)`;
  const row = rows[0];
  if (!row) throw new Error("Device claim failure accounting returned no result");
  return { failedAttempts: row.failed_attempts, invalidated: row.invalidated };
}

export async function claimDeviceAtomic(input: DeviceClaimAtomicInput): Promise<DeviceClaimAtomicResult> {
  const rows = await getPrivateSql()<Array<{ device_id: string; family_id: string; child_id: string }>>`
    select device_id, family_id, child_id from private.claim_device_atomic(${input.authUserId}::uuid, ${input.codeDigest}::text, ${input.publicKeySpki}::text, ${input.displayName}::text, ${input.model}::text, ${input.androidVersion}::text, ${input.supervisionMode}::text)`;
  const row = rows[0];
  if (!row) throw new Error("Device claim returned no result");
  return { deviceId: row.device_id, familyId: row.family_id, childId: row.child_id };
}

export async function loadDeviceSecurity(deviceId: string): Promise<DeviceSecurityResult | null> {
  const rows = await getPrivateSql()<Array<{ device_id: string; family_id: string; child_id: string; auth_user_id: string; public_key_spki: string; revoked_at: string | null }>>`
    select ds.device_id, d.family_id, d.child_id, ds.auth_user_id, ds.public_key_spki, d.revoked_at
    from private.device_security ds
    join public.devices_public d on d.id = ds.device_id
    where ds.device_id = ${deviceId}::uuid
    limit 1`;
  const row = rows[0];
  return row ? { deviceId: row.device_id, familyId: row.family_id, childId: row.child_id, authUserId: row.auth_user_id, publicKeySpki: row.public_key_spki, revokedAt: row.revoked_at } : null;
}

export async function claimDeviceRequestNonceAtomic(deviceId: string, nonce: string, timestamp: number): Promise<boolean> {
  const rows = await getPrivateSql()<Array<{ claimed: boolean }>>`
    select private.claim_device_request_nonce(${deviceId}::uuid, ${nonce}::text, ${timestamp}::bigint) as claimed`;
  return rows[0]?.claimed === true;
}

async function queryDeviceSyncAtomic(input: DeviceSyncAtomicInput): Promise<DeviceSyncAtomicRow[]> {
  return await getPrivateSql()<DeviceSyncAtomicRow[]>`
    select desired_state, desired_state_version, commands
    from private.harbor_sync_device(${input.deviceId}::uuid, ${input.acknowledgedDesiredStateVersion}::bigint, ${input.appliedCommandIds}::uuid[])`;
}

export async function syncDeviceAtomicWithQuery(input: DeviceSyncAtomicInput, query: DeviceSyncAtomicQuery): Promise<DeviceSyncAtomicResult> {
  const row = (await query(input))[0];
  if (!row) throw new Error("Device sync returned no result");
  return { desiredState: row.desired_state, desiredStateVersion: row.desired_state_version, commands: row.commands };
}

export async function syncDeviceAtomic(input: DeviceSyncAtomicInput): Promise<DeviceSyncAtomicResult> {
  return await syncDeviceAtomicWithQuery(input, queryDeviceSyncAtomic);
}

async function queryUpdateDeviceDesiredStateAtomic(input: DesiredStateAtomicInput) {
  return await getPrivateSql()<Array<{ desired_state_version: number }>>`
    select desired_state_version from private.harbor_update_device_desired_state(${input.deviceId}::uuid, ${input.familyId}::uuid, ${input.actorUserId}::uuid, ${JSON.stringify(input.desiredState)}::jsonb, ${input.expectedVersion}::bigint)`;
}

export async function updateDeviceDesiredStateAtomicWithQuery(input: DesiredStateAtomicInput, query: DesiredStateAtomicQuery) {
  const row = (await query(input))[0];
  if (!row) throw new Error("Desired-state update returned no result");
  return { desiredStateVersion: row.desired_state_version };
}

export async function updateDeviceDesiredStateAtomic(input: DesiredStateAtomicInput) {
  return await updateDeviceDesiredStateAtomicWithQuery(input, queryUpdateDeviceDesiredStateAtomic);
}

async function queryRegisterDeviceFcmAtomic(input: RegisterDeviceFcmAtomicInput) {
  return await getPrivateSql()<Array<{ registered: boolean }>>`
    select private.harbor_register_device_fcm(${input.deviceId}::uuid, ${input.token}::text) as registered`;
}

export async function registerDeviceFcmAtomicWithQuery(input: RegisterDeviceFcmAtomicInput, query: RegisterDeviceFcmAtomicQuery): Promise<boolean> {
  const row = (await query(input))[0];
  if (!row?.registered) throw new Error("FCM registration was not confirmed");
  return true;
}

export async function registerDeviceFcmAtomic(input: RegisterDeviceFcmAtomicInput): Promise<boolean> {
  return await registerDeviceFcmAtomicWithQuery(input, queryRegisterDeviceFcmAtomic);
}

async function queryRevokeDeviceAtomic(input: RevokeDeviceAtomicInput) {
  return await getPrivateSql()<Array<{ revoked: boolean }>>`
    select private.harbor_revoke_device(${input.deviceId}::uuid, ${input.familyId}::uuid, ${input.actorUserId}::uuid) as revoked`;
}

export async function revokeDeviceAtomicWithQuery(input: RevokeDeviceAtomicInput, query: RevokeDeviceAtomicQuery): Promise<boolean> {
  const row = (await query(input))[0];
  if (!row?.revoked) throw new Error("Device revocation was not confirmed");
  return true;
}

export async function revokeDeviceAtomic(input: RevokeDeviceAtomicInput): Promise<boolean> {
  return await revokeDeviceAtomicWithQuery(input, queryRevokeDeviceAtomic);
}
