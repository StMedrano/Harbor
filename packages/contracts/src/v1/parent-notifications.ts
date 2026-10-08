export interface RegisterParentFcmRequestV1 {
  clientInstallationId: string;
  token: string;
}
export interface RegisterParentFcmReplyV1 {
  registrationId: string;
  active: true;
}
export interface RemoveParentFcmRequestV1 {
  clientInstallationId: string;
}
