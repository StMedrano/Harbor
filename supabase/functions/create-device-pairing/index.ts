import { requireParent, type ParentContext } from "../_shared/auth.ts";
import { issueDevicePairingAtomic } from "../_shared/clients.ts";
import { HarborAuthError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

export type PairingPersistenceInput = { parentUserId: string; childId: string; codeDigest: string; expiresAt: string };
export type CreateDevicePairingDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  now(): Date;
  generatePairingCode(): string;
  digestPairingCode(code: string): Promise<string>;
  issuePairingAtomic(input: PairingPersistenceInput): Promise<{ familyId: string; childId: string; expiresAt: string }>;
};

function randomCode(): string {
  const bytes = new Uint32Array(1);
  crypto.getRandomValues(bytes);
  return String(bytes[0] % 1_000_000).padStart(6, "0");
}
async function digest(code: string): Promise<string> {
  const pepper = Deno.env.get("HARBOR_PAIRING_PEPPER")?.trim();
  if (!pepper) throw new Error("Missing required environment variable: HARBOR_PAIRING_PEPPER");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(code)));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
const defaults: CreateDevicePairingDependencies = { requireParent, now: () => new Date(), generatePairingCode: randomCode, digestPairingCode: digest, issuePairingAtomic: issueDevicePairingAtomic };

export async function handleCreateDevicePairing(req: Request, deps: CreateDevicePairingDependencies): Promise<Response> {
  try {
    const parent = await deps.requireParent(req);
    let body: Record<string, unknown>;
    try { body = await req.json(); } catch { return jsonError("VALIDATION_FAILED", 400, "Request body must be valid JSON"); }
    if (typeof body.childId !== "string" || !body.childId.trim()) return jsonError("VALIDATION_FAILED", 400, "Child id is required");
    const code = deps.generatePairingCode();
    if (!/^\d{6}$/.test(code)) throw new Error("Pairing generator must return exactly six digits");
    const expiresAt = new Date(deps.now().getTime() + 10 * 60 * 1000).toISOString();
    const codeDigest = await deps.digestPairingCode(code);
    const result = await deps.issuePairingAtomic({ parentUserId: parent.userId, childId: body.childId.trim(), codeDigest, expiresAt });
    return new Response(JSON.stringify({ code, expiresAt: result.expiresAt }), { status: 200, headers: { "content-type": "application/json; charset=utf-8" } });
  } catch (error) {
    if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
    throw error;
  }
}
if (import.meta.main) Deno.serve((req) => handleCreateDevicePairing(req, defaults));
