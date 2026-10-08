import { type ParentContext, requireParent } from "./auth.ts";
import { getPrivateSql } from "./clients.ts";
import { HarborAuthError } from "./errors.ts";
export type ParentSessionDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  isActive(userId: string, sessionId: string): Promise<boolean>;
};
export async function isActiveParentSession(
  userId: string,
  sessionId: string,
): Promise<boolean> {
  const rows = await getPrivateSql()<
    Array<{ active: boolean }>
  >`select private.harbor_parent_session_active(${userId}::uuid,${sessionId}::uuid) as active`;
  return rows[0]?.active === true;
}
export async function requireActiveParentSession(
  request: Request,
  deps: ParentSessionDependencies = {
    requireParent,
    isActive: isActiveParentSession,
  },
): Promise<ParentContext & { sessionId: string }> {
  const parent = await deps.requireParent(request);
  if (
    typeof parent.sessionId !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      parent.sessionId,
    )
  ) {
    throw new HarborAuthError(
      "AUTH_REQUIRED",
      401,
      "Current parent session is required",
    );
  }
  const sessionId = parent.sessionId.toLowerCase();
  if (!await deps.isActive(parent.userId, sessionId)) {
    throw new HarborAuthError(
      "AUTH_REQUIRED",
      401,
      "Current parent session is required",
    );
  }
  return { ...parent, sessionId };
}
