export const developmentUrl = "https://bfvybxkjxilntjgndsrm.supabase.co";
export function parsePublicConfig(
  value: unknown,
): { supabaseUrl: string; publishableKey: string; vapidPublicKey: string } {
  if (!value || typeof value !== "object") {
    throw Error("Enter development public configuration.");
  }
  const c = value as Record<string, unknown>;
  if (c.supabaseUrl !== developmentUrl) {
    throw Error("Only the approved development backend is allowed.");
  }
  if (
    typeof c.publishableKey !== "string" ||
    !/^sb_publishable_[A-Za-z0-9_-]+$/.test(c.publishableKey)
  ) throw Error("Use a public publishable key, never a server key.");
  if (
    typeof c.vapidPublicKey !== "string" ||
    !/^[A-Za-z0-9_-]{87}$/.test(c.vapidPublicKey) ||
    atob(c.vapidPublicKey.replaceAll("-", "+").replaceAll("_", "/") + "=")
        .charCodeAt(0) !== 4
  ) throw Error("Enter the existing public VAPID key.");
  return {
    supabaseUrl: c.supabaseUrl,
    publishableKey: c.publishableKey,
    vapidPublicKey: c.vapidPublicKey,
  };
}
