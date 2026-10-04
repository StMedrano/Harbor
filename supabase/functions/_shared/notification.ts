import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";

const routeKeys = new Set(["version", "kind", "familyId", "childId", "deviceId", "resourceId"]);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function notificationRoute(value: unknown): NotificationRouteRefV1 | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const route = value as Record<string, unknown>;
  if (route.version !== 1 || typeof route.kind !== "string" || !route.kind.trim() || route.kind.length > 128) return null;
  for (const key of Object.keys(route)) {
    if (!routeKeys.has(key)) return null;
    if (key !== "version" && key !== "kind" && (typeof route[key] !== "string" || !uuid.test(route[key] as string))) return null;
  }
  return { ...route, version: 1, kind: route.kind };
}
