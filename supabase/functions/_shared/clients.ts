import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.105.0";
import postgres from "npm:postgres@3.4.7";

export type StaffAuthorizationRow = {
  role: "support" | "admin";
};

export type CreateFamilyAtomicInput = {
  userId: string;
  name: string;
  idempotencyKey: string;
};

export type CreateFamilyAtomicRow = {
  family_id: string;
  name: string;
  role: "owner";
};

export type CreateFamilyAtomicResult = {
  familyId: string;
  name: string;
  role: "owner";
};

export type CreateFamilyAtomicQuery = (
  input: CreateFamilyAtomicInput,
) => Promise<CreateFamilyAtomicRow[]>;

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function resolvePublishableKey(): string {
  const direct = Deno.env.get("SUPABASE_PUBLISHABLE_KEY")?.trim() ||
    Deno.env.get("HARBOR_SUPABASE_PUBLISHABLE_KEY")?.trim();
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

  // Local Supabase still injects the legacy anon key. It is browser-safe and
  // remains a compatibility fallback while hosted environments move to the
  // publishable-key model.
  const legacy = Deno.env.get("SUPABASE_ANON_KEY")?.trim();
  if (legacy) return legacy;

  throw new Error("No Supabase publishable key is configured");
}

export function createRequestClient(accessToken: string): SupabaseClient {
  const supabaseUrl = requiredEnv("SUPABASE_URL");
  const publishableKey = resolvePublishableKey();

  return createClient(supabaseUrl, publishableKey, {
    global: {
      headers: { Authorization: `Bearer ${accessToken}` },
    },
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
  });
}

let privateSql: ReturnType<typeof postgres> | undefined;

function getPrivateSql() {
  if (!privateSql) {
    privateSql = postgres(requiredEnv("SUPABASE_DB_URL"), {
      prepare: false,
      max: 3,
      idle_timeout: 20,
    });
  }
  return privateSql;
}

export async function queryStaffAuthorization(
  userId: string,
): Promise<StaffAuthorizationRow | null> {
  const sql = getPrivateSql();
  const rows = await sql<StaffAuthorizationRow[]>`
    select role
    from private.staff_authorizations
    where user_id = ${userId}::uuid
    limit 1
  `;
  return rows[0] ?? null;
}

async function queryCreateFamilyAtomic(
  input: CreateFamilyAtomicInput,
): Promise<CreateFamilyAtomicRow[]> {
  const sql = getPrivateSql();
  return await sql<CreateFamilyAtomicRow[]>`
    select family_id, name, role
    from private.create_family_atomic(
      ${input.userId}::uuid,
      ${input.name}::text,
      ${input.idempotencyKey}::text
    )
  `;
}

export async function createFamilyAtomicWithQuery(
  input: CreateFamilyAtomicInput,
  query: CreateFamilyAtomicQuery,
): Promise<CreateFamilyAtomicResult> {
  const rows = await query(input);
  const row = rows[0];
  if (!row) throw new Error("Atomic family creation returned no result");
  if (row.role !== "owner") {
    throw new Error("Atomic family creation returned an invalid role");
  }

  return {
    familyId: row.family_id,
    name: row.name,
    role: "owner",
  };
}

export async function createFamilyAtomic(
  input: CreateFamilyAtomicInput,
): Promise<CreateFamilyAtomicResult> {
  return await createFamilyAtomicWithQuery(input, queryCreateFamilyAtomic);
}
