export const ContractVersion = 1 as const;

export type {
  ChildV1,
  FamilyMemberRoleV1,
  FamilyMemberStatusV1,
  FamilyMemberV1,
  FamilyV1,
} from "./v1/family.ts";

export type {
  DevicePublicStateV1,
  DeviceStatusV1,
  DeviceSupervisionModeV1,
} from "./v1/device.ts";

export type { AalLevel } from "./v1/auth.ts";
export { HarborErrorCodes } from "./v1/errors.ts";
export type { HarborErrorCode } from "./v1/errors.ts";
export type { NotificationRouteRefV1 } from "./v1/notifications.ts";
