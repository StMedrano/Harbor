export interface FamilyV1 {
  version: 1;
  id: string;
  name: string;
  timezone: string;
  createdAt: string;
  updatedAt: string;
}

export type FamilyMemberRoleV1 = "owner" | "parent";
export type FamilyMemberStatusV1 = "active" | "invited" | "removed";

export interface FamilyMemberV1 {
  version: 1;
  id: string;
  familyId: string;
  userId: string;
  role: FamilyMemberRoleV1;
  status: FamilyMemberStatusV1;
  createdAt: string;
  updatedAt: string;
}

export interface ChildV1 {
  version: 1;
  id: string;
  familyId: string;
  displayName: string;
  createdAt: string;
  updatedAt: string;
}
