import { getPrivateSql } from "./clients.ts";

/** Letters and digits a person can read aloud and type: no I, L, O or U (Crockford base 32). */
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const INVITE_LIFETIME_MS = 24 * 60 * 60 * 1000;

/** Eight characters (40 bits), shown as ABCD-EFGH. */
export function generateInviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => ALPHABET[b % 32]).join("");
}

/** What the person typed, as the canonical code, or null. Spaces, dashes and case do not matter; O reads as 0, I and L as 1. */
export function normalizeInviteCode(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 32) return null;
  const code = value.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  return code.length === 8 && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

export function formatInviteCode(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

/** Keyed digest of a code; only this is stored. Separate from device pairing codes, which share the key. */
export async function digestInviteCode(code: string): Promise<string> {
  const pepper = Deno.env.get("HARBOR_PAIRING_PEPPER")?.trim();
  if (!pepper) throw new Error("Missing required environment variable: HARBOR_PAIRING_PEPPER");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`parent-invite:${code}`)));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export type IssueInviteInput = { userId: string; familyId: string; codeDigest: string; expiresAt: string };
export async function issueParentInvite(input: IssueInviteInput): Promise<{ familyId: string; expiresAt: string }> {
  const rows = await getPrivateSql()<Array<{ family_id: string; expires_at: string }>>`
    select family_id, expires_at from private.issue_parent_invite(${input.userId}::uuid, ${input.familyId}::uuid, ${input.codeDigest}::text, ${input.expiresAt}::timestamptz)`;
  const row = rows[0];
  if (!row) throw new Error("Parent invite returned no result");
  return { familyId: row.family_id, expiresAt: new Date(row.expires_at).toISOString() };
}

export type AcceptOutcome = "joined" | "already_member" | "invalid" | "locked";
export type AcceptInviteResult = { familyId: string | null; familyName: string | null; outcome: AcceptOutcome };
export async function acceptParentInvite(userId: string, codeDigest: string): Promise<AcceptInviteResult> {
  const rows = await getPrivateSql()<Array<{ family_id: string | null; family_name: string | null; outcome: AcceptOutcome }>>`
    select family_id, family_name, outcome from private.accept_parent_invite(${userId}::uuid, ${codeDigest}::text)`;
  const row = rows[0];
  if (!row || !["joined", "already_member", "invalid", "locked"].includes(row.outcome)) throw new Error("Parent invite acceptance returned no result");
  return { familyId: row.family_id, familyName: row.family_name, outcome: row.outcome };
}
