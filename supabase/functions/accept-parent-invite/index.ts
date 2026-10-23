import { serveHarbor } from "../_shared/http.ts";
import { requireParent, type ParentContext } from "../_shared/auth.ts";
import { HarborAuthError, databaseError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";
import { acceptParentInvite, digestInviteCode, normalizeInviteCode, type AcceptInviteResult } from "../_shared/parent-invites.ts";

/**
 * An adult signed in with their own account enters a parent's code and joins that family as a parent. A wrong,
 * used or expired code is refused the same way, and after 10 wrong codes in an hour the account is refused for the
 * rest of the hour. A child phone's anonymous identity is never a parent.
 */
export type AcceptParentInviteDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  digestCode(code: string): Promise<string>;
  accept(userId: string, codeDigest: string): Promise<AcceptInviteResult>;
};

const defaults: AcceptParentInviteDependencies = { requireParent, digestCode: digestInviteCode, accept: acceptParentInvite };
const INVALID = "That code isn't valid or has expired";

export async function handleAcceptParentInvite(req: Request, deps: AcceptParentInviteDependencies): Promise<Response> {
  try {
    const parent = await deps.requireParent(req);
    let body: Record<string, unknown>;
    try { body = await req.json(); } catch { return jsonError("VALIDATION_FAILED", 400, "Request body must be valid JSON"); }
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).join() !== "code") {
      return jsonError("VALIDATION_FAILED", 400, INVALID);
    }
    const code = normalizeInviteCode(body.code);
    // A code that cannot exist still counts as a try, so the format check gives nothing away.
    const result = await deps.accept(parent.userId, await deps.digestCode(code ?? "-"));
    if (result.outcome === "locked") return jsonError("FORBIDDEN", 403, "Too many tries. Wait an hour, then ask for a new code");
    if (result.outcome === "invalid" || !result.familyId) return jsonError("VALIDATION_FAILED", 400, INVALID);
    return Response.json({ familyId: result.familyId, familyName: result.familyName, joined: result.outcome === "joined" });
  } catch (error) {
    error = databaseError(error) ?? error;
    if (error instanceof HarborAuthError) return jsonError(error.code, error.status, error.message);
    throw error;
  }
}

if (import.meta.main) serveHarbor((req) => handleAcceptParentInvite(req, defaults));
