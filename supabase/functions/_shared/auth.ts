import type { AalLevel } from "../../../packages/contracts/src/v1/auth.ts";
import {
  createRequestClient,
  queryStaffAuthorization,
} from "./clients.ts";
import { requireRecentAal2 } from "./aal.ts";
import { HarborAuthError } from "./errors.ts";

export type AmrEntry = {
  method: string;
  timestamp: number;
};

export type ParentContext = {
  userId: string;
  accessToken: string;
  aal: AalLevel;
  amr: AmrEntry[];
};

export type ParentUser = {
  id: string;
  isAnonymous: boolean;
};

export type ValidatedAccessToken = {
  user: ParentUser;
  claims: {
    aal?: AalLevel;
    amr?: AmrEntry[];
  };
};

export type FamilyRole = "owner" | "parent";
export type StaffRole = "support" | "admin";

export type AuthDependencies = {
  validateAccessToken(accessToken: string): Promise<ValidatedAccessToken | null>;
  getFamilyMembership(
    userId: string,
    familyId: string,
  ): Promise<{ role: FamilyRole; status: string } | null>;
  getStaffAuthorization(userId: string): Promise<{ role: StaffRole } | null>;
  nowEpochSeconds(): number;
};

function normalizeAal(value: unknown): AalLevel {
  return value === "aal2" ? "aal2" : "aal1";
}

function normalizeAmr(value: unknown): AmrEntry[] {
  if (!Array.isArray(value)) return [];

  const normalized: AmrEntry[] = [];
  for (const entry of value) {
    if (!entry || typeof entry !== "object") continue;
    const record = entry as Record<string, unknown>;
    if (typeof record.method !== "string") continue;
    if (typeof record.timestamp !== "number" || !Number.isFinite(record.timestamp)) continue;
    normalized.push({ method: record.method, timestamp: record.timestamp });
  }
  return normalized;
}

function createDefaultAuthDependencies(accessToken: string): AuthDependencies {
  // A new caller-scoped client is created for every invocation. It is never
  // cached globally because its Authorization header carries caller identity.
  const requestClient = createRequestClient(accessToken);

  return {
    async validateAccessToken(token: string) {
      const [claimsResult, userResult] = await Promise.all([
        requestClient.auth.getClaims(),
        requestClient.auth.getUser(token),
      ]);

      if (claimsResult.error || userResult.error || !userResult.data.user) {
        return null;
      }

      const rawClaims = (claimsResult.data?.claims ?? {}) as Record<string, unknown>;
      const rawUser = userResult.data.user as typeof userResult.data.user & {
        is_anonymous?: boolean;
      };

      return {
        user: {
          id: rawUser.id,
          isAnonymous: rawUser.is_anonymous === true || rawClaims.is_anonymous === true,
        },
        claims: {
          aal: normalizeAal(rawClaims.aal),
          amr: normalizeAmr(rawClaims.amr),
        },
      };
    },

    async getFamilyMembership(userId: string, familyId: string) {
      const { data, error } = await requestClient
        .from("family_members")
        .select("role,status")
        .eq("family_id", familyId)
        .eq("user_id", userId)
        .maybeSingle();

      if (error) throw error;
      if (!data) return null;
      if (data.role !== "owner" && data.role !== "parent") return null;
      return { role: data.role, status: data.status };
    },

    getStaffAuthorization: queryStaffAuthorization,
    nowEpochSeconds: () => Math.floor(Date.now() / 1000),
  };
}

function bearerToken(req: Request): string {
  const value = req.headers.get("authorization")?.trim() ?? "";
  const match = /^Bearer\s+(\S+)$/i.exec(value);
  if (!match) {
    throw new HarborAuthError("AUTH_REQUIRED", 401, "Authentication is required");
  }
  return match[1];
}

export async function requireParent(
  req: Request,
  dependencies?: AuthDependencies,
): Promise<ParentContext> {
  const accessToken = bearerToken(req);
  const activeDependencies = dependencies ?? createDefaultAuthDependencies(accessToken);
  const validated = await activeDependencies.validateAccessToken(accessToken);

  if (!validated) {
    throw new HarborAuthError("AUTH_REQUIRED", 401, "Authentication is required");
  }

  if (validated.user.isAnonymous) {
    throw new HarborAuthError("FORBIDDEN", 403, "Parent access is required");
  }

  return {
    userId: validated.user.id,
    accessToken,
    aal: validated.claims.aal ?? "aal1",
    amr: validated.claims.amr ?? [],
  };
}

export async function requireFamilyRole(
  ctx: ParentContext,
  familyId: string,
  allowedRoles: FamilyRole[],
  dependencies?: AuthDependencies,
): Promise<void> {
  const activeDependencies = dependencies ?? createDefaultAuthDependencies(ctx.accessToken);
  const membership = await activeDependencies.getFamilyMembership(ctx.userId, familyId);

  if (
    !membership ||
    membership.status !== "active" ||
    !allowedRoles.includes(membership.role)
  ) {
    throw new HarborAuthError("FORBIDDEN", 403, "Active family authorization is required");
  }
}

export async function requireStaffRole(
  ctx: ParentContext,
  allowedRoles: StaffRole[],
  dependencies?: AuthDependencies,
): Promise<void> {
  const activeDependencies = dependencies ?? createDefaultAuthDependencies(ctx.accessToken);

  requireRecentAal2(ctx, activeDependencies.nowEpochSeconds(), 900);

  const authorization = await activeDependencies.getStaffAuthorization(ctx.userId);
  if (!authorization || !allowedRoles.includes(authorization.role)) {
    throw new HarborAuthError("FORBIDDEN", 403, "Staff authorization is required");
  }
}
