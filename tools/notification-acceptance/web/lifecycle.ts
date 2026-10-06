export interface Subscription {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}
export function installationStore(
  storage: Pick<Storage, "getItem" | "setItem">,
) {
  return {
    getOrCreate(): string {
      const key = "harbor.acceptance.installationId";
      const existing = storage.getItem(key);
      if (
        existing &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
          .test(existing)
      ) return existing;
      const id = crypto.randomUUID();
      storage.setItem(key, id);
      return id;
    },
  };
}
export interface PushLifecycleDependencies {
  requireSession(): Promise<void>;
  installation: ReturnType<typeof installationStore>;
  requestPermission(): Promise<"granted" | "denied" | "default">;
  getSubscription(): Promise<Subscription | null>;
  subscribe(): Promise<Subscription>;
  register(id: string, subscription: Subscription): Promise<void>;
  remove(id: string, endpoint?: string): Promise<void>;
  unsubscribe(): Promise<void>;
}
export async function enablePush(
  deps: PushLifecycleDependencies,
): Promise<{ installationId: string; endpoint: string }> {
  await deps.requireSession();
  if (await deps.requestPermission() !== "granted") {
    throw Error(
      "Notification permission is required. Change the browser permission and retry.",
    );
  }
  const installationId = deps.installation.getOrCreate();
  const subscription = await deps.getSubscription() ?? await deps.subscribe();
  await deps.register(installationId, subscription);
  return { installationId, endpoint: subscription.endpoint };
}
export async function removePush(
  deps: PushLifecycleDependencies,
): Promise<void> {
  await deps.requireSession();
  const subscription = await deps.getSubscription();
  await deps.remove(deps.installation.getOrCreate(), subscription?.endpoint);
  await deps.unsubscribe();
}
