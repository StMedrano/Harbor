import type { ParentContext } from "../_shared/auth.ts";

export type CreateFamilyPersistenceInput = {
  userId: string;
  name: string;
  idempotencyKey: string;
};

export type CreateFamilyResult = {
  familyId: string;
  name: string;
  role: "owner";
};

export type CreateFamilyDependencies = {
  requireParent(req: Request): Promise<ParentContext>;
  createFamilyAtomic(input: CreateFamilyPersistenceInput): Promise<CreateFamilyResult>;
};

export async function handleCreateFamily(
  _req: Request,
  _dependencies: CreateFamilyDependencies,
): Promise<Response> {
  throw new Error("NOT_IMPLEMENTED");
}
