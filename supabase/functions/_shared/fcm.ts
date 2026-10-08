import type { JWT } from "npm:google-auth-library@10.5.0";
import { notificationRoute } from "./notification.ts";
import type { PushDeliveryResult } from "./web-push.ts";

export type FcmDependencies = {
  projectId: string;
  getAccessToken(): Promise<string>;
  fetch(url: string, options: RequestInit): Promise<Response>;
};

export async function sendFcmWithDependencies(token: string, value: unknown, dependencies: FcmDependencies, parentRegistrationId?: string): Promise<PushDeliveryResult> {
  const route = notificationRoute(value);
  if (!route || !token.trim() || !/^[a-z0-9][a-z0-9-]{0,62}$/.test(dependencies.projectId)) return { status: "permanent_failure", reason: "provider_rejected" };
  let accessToken: string;
  try {
    accessToken = await dependencies.getAccessToken();
    if (!accessToken) throw new Error("Missing OAuth access token");
  } catch {
    return { status: "retryable_failure", reason: "provider_error" };
  }
  let response: Response;
  try {
    response = await dependencies.fetch(`https://fcm.googleapis.com/v1/projects/${dependencies.projectId}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
      body: JSON.stringify({ message: { token, data: parentRegistrationId ? { route: JSON.stringify(route), parentRegistrationId } : { route: JSON.stringify(route) }, android: { priority: "high" } } }),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    return { status: "retryable_failure", reason: "network_error" };
  }
  if (response.status === 404) {
    try {
      const body = await response.json();
      if (Array.isArray(body?.error?.details) && body.error.details.some((detail: unknown) => {
        if (!detail || typeof detail !== "object") return false;
        const value = detail as Record<string, unknown>;
        return value["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError" && value.errorCode === "UNREGISTERED";
      })) return { status: "permanent_failure", reason: "invalid_token" };
    } catch { /* Malformed provider content never invalidates a token. */ }
    return { status: "permanent_failure", reason: "provider_rejected" };
  }
  await response.body?.cancel();
  if (response.ok) return { status: "sent" };
  if (response.status === 429) return { status: "retryable_failure", reason: "rate_limited" };
  if (response.status >= 500 || response.status === 401 || response.status === 403) return { status: "retryable_failure", reason: "provider_error" };
  return { status: "permanent_failure", reason: "provider_rejected" };
}

let authClient: JWT | undefined;
export async function sendFcm(token: string, route: unknown, parentRegistrationId?: string): Promise<PushDeliveryResult> {
  try {
    const credentials = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "{}");
    if (credentials.type !== "service_account" || typeof credentials.client_email !== "string" || typeof credentials.private_key !== "string" || typeof credentials.project_id !== "string") throw new Error("Invalid FCM credentials");
    if (!authClient) {
      const { JWT } = await import("npm:google-auth-library@10.5.0");
      authClient = new JWT({ email: credentials.client_email, key: credentials.private_key, scopes: ["https://www.googleapis.com/auth/firebase.messaging"] });
    }
    return await sendFcmWithDependencies(token, route, {
      projectId: credentials.project_id,
      async getAccessToken() {
        const result = await authClient!.getAccessToken();
        if (!result.token) throw new Error("Missing FCM access token");
        return result.token;
      },
      fetch,
    }, parentRegistrationId);
  } catch {
    return { status: "retryable_failure", reason: "provider_error" };
  }
}
