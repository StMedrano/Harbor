import type { AalLevel } from "../../../packages/contracts/src/v1/auth.ts";

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

export async function requireParent(
  _req: Request,
  _dependencies?: AuthDependencies,
): Promise<ParentContext> {
  throw new Error("NOT_IMPLEMENTED");
}

export async function requireFamilyRole(
  _ctx: ParentContext,
  _familyId: string,
  _allowedRoles: FamilyRole[],
  _dependencies?: AuthDependencies,
): Promise<void> {
  throw new Error("NOT_IMPLEMENTED");
}

export async function requireStaffRole(
  _ctx: ParentContext,
  _allowedRoles: StaffRole[],
  _dependencies?: AuthDependencies,
): Promise<void> {
  throw new Error("NOT_IMPLEMENTED");
}
