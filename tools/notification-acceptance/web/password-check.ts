import type { SupabaseClient } from "npm:@supabase/supabase-js@2.105.0";
export type PasswordResult =
  | "checking"
  | "accepted"
  | "rejected"
  | "unverified";
export type PasswordAuth = Pick<
  SupabaseClient["auth"],
  "signInWithPassword" | "signOut"
>;
export async function checkPassword(
  auth: PasswordAuth,
  email: string,
  password: string,
  report: (result: PasswordResult) => void,
): Promise<boolean> {
  report("checking");
  try {
    const { data, error } = await auth.signInWithPassword({ email, password });
    if (
      error || !data.session || !data.user ||
      data.session.user.id !== data.user.id
    ) {
      report(error?.code === "invalid_credentials" ? "rejected" : "unverified");
      await auth.signOut({ scope: "local" }).catch(() => {});
      return false;
    }
    report("accepted");
    return true;
  } catch {
    report("unverified");
    await auth.signOut({ scope: "local" }).catch(() => {});
    return false;
  }
}
