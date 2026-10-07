/** Every backend failure is thrown as ApiError(code, message). `unauthenticated` returns the UI to sign-in. */
export class ApiError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
