import { serveHarbor } from "../_shared/http.ts";
import { requireParent, type ParentContext } from "../_shared/auth.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";
import {
  INVITE_LIFETIME_MS,
  digestInviteCode,
  formatInviteCode,
  generateInviteCode,
  issueParentInvite,
  normalizeInviteCode,
  type IssueInviteInput,
} from "../_shared/parent-invites.ts";

/** A parent asks for a code another adult enters to join the family as a parent. */
export type CreateParentInviteDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  now(): Date;
  generateCode(): string;
  digestCode(code: string): Promise<string>;
  issue(input: IssueInviteInput): Promise<{ familyId: string; expiresAt: string }>;
};

const defaults: CreateParentInviteDependencies = {
  requireParent,
  now: () => new Date(),
  generateCode: generateInviteCode,
  digestCode: digestInviteCode,
  issue: issueParentInvite,
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export async function handleCreateParentInvite(req: Request, deps: CreateParentInviteDependencies): Promise<Response> {
  try {
    const parent = await deps.requireParent(req);
    let body: Record<string, unknown>;
    try { body = await req.json(); } catch { return jsonError("VALIDATION_FAILED", 400, "Request body must be valid JSON"); }
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).join() !== "familyId" ||
      typeof body.familyId !== "string" || !UUID.test(body.familyId)) {
      return jsonError("VALIDATION_FAILED", 400, "Family id is required");
    }
    const code = deps.generateCode();
    if (normalizeInviteCode(code) !== code) throw new Error("Invite generator must return eight code characters");
    const expiresAt = new Date(deps.now().getTime() + INVITE_LIFETIME_MS).toISOString();
    const result = await deps.issue({ userId: parent.userId, familyId: body.familyId, codeDigest: await deps.digestCode(code), expiresAt });
    return Response.json({ code: formatInviteCode(code), expiresAt: result.expiresAt });
  } catch (error) {
    error = databaseError(error) ?? error;
    if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
    throw error;
  }
}

if (import.meta.main) serveHarbor((req) => handleCreateParentInvite(req, defaults));
