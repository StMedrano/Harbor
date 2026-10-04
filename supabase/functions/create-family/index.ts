import type { ParentContext } from "../_shared/auth.ts";
import { HarborAuthError } from "../_shared/errors.ts";
import { jsonError } from "../_shared/responses.ts";

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

function validationError(message: string): Response {
  return jsonError("VALIDATION_FAILED", 400, message);
}

function jsonResult(result: CreateFamilyResult): Response {
  return new Response(JSON.stringify(result), {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function mapKnownError(error: unknown): Response | undefined {
  if (error instanceof HarborAuthError) {
    return jsonError(error.code, error.status, error.message);
  }
  return undefined;
}

export async function handleCreateFamily(
  req: Request,
  dependencies: CreateFamilyDependencies,
): Promise<Response> {
  let parent: ParentContext;
  try {
    parent = await dependencies.requireParent(req);
  } catch (error) {
    const response = mapKnownError(error);
    if (response) return response;
    throw error;
  }

  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    return validationError("Request body must be valid JSON");
  }

  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return validationError("Request body must be a JSON object");
  }

  const body = payload as Record<string, unknown>;
  if (typeof body.name !== "string") {
    return validationError("Family name is required");
  }
  if (typeof body.idempotencyKey !== "string") {
    return validationError("Idempotency key is required");
  }

  const name = body.name.trim();
  const idempotencyKey = body.idempotencyKey.trim();
  const nameLength = Array.from(name).length;

  if (nameLength < 1 || nameLength > 100) {
    return validationError("Family name must contain 1 to 100 Unicode code points");
  }
  if (!idempotencyKey) {
    return validationError("Idempotency key is required");
  }

  try {
    const result = await dependencies.createFamilyAtomic({
      userId: parent.userId,
      name,
      idempotencyKey,
    });
    return jsonResult(result);
  } catch (error) {
    const response = mapKnownError(error);
    if (response) return response;
    throw error;
  }
}
