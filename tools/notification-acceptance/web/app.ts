/// <reference lib="dom" />
import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.105.0";
import { parsePublicConfig } from "./config.ts";
import {
  enablePush,
  installationStore,
  type PushLifecycleDependencies,
  removePush,
  type Subscription,
} from "./lifecycle.ts";

let client: SupabaseClient | undefined;
let deps: PushLifecycleDependencies | undefined;
const status = document.querySelector<HTMLElement>("#status")!;
const form = document.querySelector<HTMLFormElement>("form")!;
function input(name: string) {
  return form.elements.namedItem(name) as HTMLInputElement;
}
function subscriptionValue(s: PushSubscription): Subscription {
  const json = s.toJSON();
  if (!json.keys?.p256dh || !json.keys?.auth) {
    throw Error("Browser subscription keys are missing.");
  }
  return {
    endpoint: s.endpoint,
    keys: { p256dh: json.keys.p256dh, auth: json.keys.auth },
  };
}
async function action(work: () => Promise<void>) {
  for (const button of document.querySelectorAll<HTMLButtonElement>("button")) {
    button.disabled = true;
  }
  try {
    await work();
  } catch {
    status.textContent =
      "Setup or request failed. Check configuration, sign-in and notification permission, then retry. Registration is unconfirmed.";
  } finally {
    for (
      const button of document.querySelectorAll<HTMLButtonElement>("button")
    ) button.disabled = false;
  }
}
form.addEventListener("submit", (e) => {
  e.preventDefault();
  void action(async () => {
    deps = undefined;
    if (client) await client.auth.signOut({ scope: "local" });
    client = undefined;
    const config = parsePublicConfig({
      supabaseUrl: input("url").value,
      publishableKey: input("key").value,
      vapidPublicKey: input("vapid").value,
    });
    const next = createClient(config.supabaseUrl, config.publishableKey, {
      auth: {
        persistSession: false,
        detectSessionInUrl: false,
        autoRefreshToken: true,
      },
    });
    const email = input("email").value, password = input("password").value;
    input("password").value = "";
    const { error } = await next.auth.signInWithPassword({ email, password });
    if (error) {
      await next.auth.signOut({ scope: "local" });
      throw error;
    }
    client = next;
    const registration = await navigator.serviceWorker.register(
      "/service-worker.js",
      { type: "module" },
    );
    await navigator.serviceWorker.ready;
    const invoke = async (name: string, body: Record<string, unknown>) => {
      const { error } = await next.functions.invoke(name, { body });
      if (error) throw Error("Backend request failed.");
    };
    deps = {
      requireSession: async () => {
        const { data, error } = await next.auth.getSession();
        if (error || !data.session) throw Error("Sign in again.");
      },
      installation: installationStore(localStorage),
      requestPermission: () => Notification.requestPermission(),
      getSubscription: async () => {
        const s = await registration.pushManager.getSubscription();
        return s ? subscriptionValue(s) : null;
      },
      subscribe: async () => {
        const key = Uint8Array.from(
          atob(
            config.vapidPublicKey.replaceAll("-", "+").replaceAll("_", "/") +
              "=",
          ),
          (c) => c.charCodeAt(0),
        );
        return subscriptionValue(
          await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: key,
          }),
        );
      },
      register: (id, s) =>
        invoke("register-web-push", { clientInstallationId: id, ...s }),
      remove: (id, endpoint) =>
        invoke("remove-web-push", {
          clientInstallationId: id,
          ...(endpoint ? { endpoint } : {}),
        }),
      unsubscribe: async () => {
        const s = await registration.pushManager.getSubscription();
        if (s && !await s.unsubscribe()) {
          throw Error("Retry browser unsubscribe.");
        }
      },
    };
    status.textContent =
      "Signed in for this page session. Click Enable notifications to register.";
  });
});
document.querySelector("#enable")!.addEventListener("click", () => {
  void action(async () => {
    status.textContent = "Registration unconfirmed.";
    if (!deps) throw Error("Sign in first.");
    const result = await enablePush(deps);
    status.textContent =
      `Backend registration confirmed. Installation: ${result.installationId}. Actual delivery still requires receipt evidence.`;
  });
});
document.querySelector("#remove")!.addEventListener("click", () => {
  void action(async () => {
    if (!deps) throw Error("Sign in first.");
    await removePush(deps);
    status.textContent =
      "Backend registration removed and browser subscription unsubscribed.";
  });
});
