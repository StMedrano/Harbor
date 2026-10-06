import { acceptanceSite } from "./site.ts";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.105.0";

export type EmailFixture = {
  id: string;
  email: string;
  phase: "signup" | "confirmed" | "recovery" | "complete";
};
export type EmailAuth = Pick<
  SupabaseClient["auth"],
  "setSession" | "verifyOtp" | "getUser" | "signOut" | "updateUser"
>;
export type EmailCallback = {
  type: "signup" | "recovery";
  access_token: string;
  refresh_token: string;
};
export const emailRedirect = "http://localhost:3000/";

export async function beginEmailFixture(
  auth: SupabaseClient["auth"],
  email: string,
  password: string,
  redirect: string = emailRedirect,
): Promise<EmailFixture> {
  const { data, error } = await auth.signUp({
    email,
    password,
    options: { emailRedirectTo: acceptanceSite(redirect).pageUrl },
  });
  if (error || !data.user?.id || !data.user.identities?.length) {
    throw Error("Signup was not accepted.");
  }
  // Metadata is deliberately not used as authorization or proof of confirmation.
  return { id: data.user.id, email, phase: "signup" };
}
async function requireFixture(auth: EmailAuth, fixture: EmailFixture) {
  const { data, error } = await auth.getUser();
  if (
    error || !data.user || data.user.id !== fixture.id ||
    data.user.email?.toLowerCase() !== fixture.email.toLowerCase() ||
    !data.user.email_confirmed_at || data.user.is_anonymous
  ) {
    throw Error("Verified fixture identity is required.");
  }
}
export async function requestEmailRecovery(
  auth: SupabaseClient["auth"],
  fixture: EmailFixture,
  redirect: string = emailRedirect,
): Promise<EmailFixture> {
  if (fixture.phase !== "confirmed") throw Error("Confirm the fixture first.");
  try {
    await requireFixture(auth, fixture);
  } catch {
    await auth.signOut({ scope: "local" });
    throw Error("Verified fixture identity is required.");
  }
  const { error } = await auth.resetPasswordForEmail(fixture.email, {
    redirectTo: acceptanceSite(redirect).pageUrl,
  });
  if (error) throw Error("Recovery request was not accepted.");
  return { ...fixture, phase: "recovery" };
}

export function takeEmailCallback(
  url: string,
  replace: (url: string) => void,
  redirect: string = emailRedirect,
): EmailCallback | null {
  const parsed = new URL(url);
  if (!parsed.hash && !parsed.search) return null;
  // Scrub even malformed/error links before inspecting any credential.
  replace(acceptanceSite(redirect).pageUrl);
  const params = new URLSearchParams(parsed.hash.slice(1));
  const type = params.get("type");
  if (
    parsed.origin + parsed.pathname !== acceptanceSite(redirect).pageUrl ||
    parsed.search ||
    params.has("error") || params.has("error_code") ||
    !["type", "access_token", "refresh_token"].every((key) =>
      params.getAll(key).length === 1 && !!params.get(key)
    ) ||
    (type !== "signup" && type !== "recovery")
  ) throw Error("Email link is invalid or expired.");
  return {
    type,
    access_token: params.get("access_token")!,
    refresh_token: params.get("refresh_token")!,
  };
}

export class EmailAcceptance {
  private recoveryReady = false;
  constructor(
    private auth: EmailAuth,
    private fixture: EmailFixture,
    private redirect: string = emailRedirect,
  ) {}
  async acceptRecoveryLink(link: string): Promise<"recovery"> {
    this.recoveryReady = false;
    try {
      const url = new URL(link);
      const params = url.searchParams;
      if (
        this.fixture.phase !== "recovery" ||
        url.origin !== "https://bfvybxkjxilntjgndsrm.supabase.co" ||
        url.pathname !== "/auth/v1/verify" || url.username || url.password ||
        url.hash ||
        params.getAll("type").length !== 1 ||
        params.get("type") !== "recovery" ||
        params.getAll("token").length !== 1 || !params.get("token") ||
        params.getAll("redirect_to").length !== 1 ||
        params.get("redirect_to") !== acceptanceSite(this.redirect).pageUrl
      ) throw Error("Recovery link is invalid.");
      // The server verifies action type, expiry and single use; URL labels and
      // ordinary access/refresh tokens are never recovery proof.
      const { error } = await this.auth.verifyOtp({
        token_hash: params.get("token")!,
        type: "recovery",
      });
      if (error) throw Error("Recovery token was rejected.");
      await requireFixture(this.auth, this.fixture);
      this.recoveryReady = true;
      return "recovery";
    } catch {
      await this.auth.signOut({ scope: "local" });
      throw Error("Recovery email was not verified.");
    }
  }
  async accept(callback: EmailCallback): Promise<"confirmed"> {
    this.recoveryReady = false;
    try {
      if (callback.type !== "signup" || this.fixture.phase !== "signup") {
        throw Error("Email link does not match pending flow.");
      }
      const { error } = await this.auth.setSession({
        access_token: callback.access_token,
        refresh_token: callback.refresh_token,
      });
      if (error) throw Error("Email session was rejected.");
      await requireFixture(this.auth, this.fixture);
      return "confirmed";
    } catch {
      await this.auth.signOut({ scope: "local" });
      throw Error("Email callback was not verified.");
    }
  }
  async changePassword(password: string): Promise<void> {
    if (!this.recoveryReady) {
      throw Error("Open the requested recovery email first.");
    }
    try {
      await requireFixture(this.auth, this.fixture);
    } catch {
      this.recoveryReady = false;
      await this.auth.signOut({ scope: "local" });
      throw Error("Recovery identity was rejected.");
    }
    const { error } = await this.auth.updateUser({ password });
    if (error) {
      throw Error("Password update failed; retry with the recovery session.");
    }
    this.recoveryReady = false;
    await this.auth.signOut({ scope: "local" });
  }
}
