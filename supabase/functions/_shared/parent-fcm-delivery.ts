import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";
import {
  type FcmDependencies,
  sendFcm,
  sendFcmWithDependencies,
} from "./fcm.ts";
import type { PushDeliveryResult } from "./web-push.ts";
export type ParentFcmDeliveryTarget = {
  registrationId: string;
  userId: string;
  sessionId: string;
  token: string;
  tokenHash: string;
};
function validRegistrationId(id: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
    id,
  );
}
export async function sendParentFcmWithDependencies(
  token: string,
  route: unknown,
  registrationId: string,
  deps: FcmDependencies,
): Promise<PushDeliveryResult> {
  if (!validRegistrationId(registrationId)) {
    return { status: "permanent_failure", reason: "provider_rejected" };
  }
  return await sendFcmWithDependencies(token, route, deps, registrationId);
}
export async function sendParentFcm(
  token: string,
  route: NotificationRouteRefV1,
  registrationId: string,
): Promise<PushDeliveryResult> {
  if (!validRegistrationId(registrationId)) {
    return { status: "permanent_failure", reason: "provider_rejected" };
  }
  return await sendFcm(token, route, registrationId);
}
