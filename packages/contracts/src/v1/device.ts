export type DeviceSupervisionModeV1 = "unknown" | "standard" | "full";
export type DeviceStatusV1 = "active" | "revoked";

export interface DevicePublicStateV1 {
  version: 1;
  id: string;
  familyId: string;
  childId: string;
  displayName: string;
  supervisionMode: DeviceSupervisionModeV1;
  status: DeviceStatusV1;
  lastSeenAt: string | null;
  createdAt: string;
  updatedAt: string;
}
