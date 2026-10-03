import type { HarborErrorCode } from "../../../packages/contracts/src/v1/errors.ts";

export class HarborAuthError extends Error {
  readonly code: HarborErrorCode;
  readonly status: number;

  constructor(code: HarborErrorCode, status: number, message: string) {
    super(message);
    this.name = "HarborAuthError";
    this.code = code;
    this.status = status;
  }
}
