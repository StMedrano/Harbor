/// <reference lib="dom" />
import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.105.0";
import { acceptanceSite } from "./site.ts";
const site = acceptanceSite(location.href);
import { parsePublicConfig } from "./config.ts";
import {
  beginEmailFixture,
  EmailAcceptance,
  type EmailFixture,
  requestEmailRecovery,
  takeEmailCallback,
} from "./email-auth.ts";
import { listReceipts } from "./receipts.ts";
import {
  enablePush,
  installationStore,
  type PushLifecycleDependencies,
  removePush,
  type Subscription,
} from "./lifecycle.ts";

let client: SupabaseClient | undefined;
// Read and scrub the callback before any client initialization or request.
let emailCallback: ReturnType<typeof takeEmailCallback> = null;
let invalidEmailCallback = false;
try {
  emailCallback = takeEmailCallback(
    location.href,
    (url) => history.replaceState(null, "", url),
    site.pageUrl,
  );
} catch {
  invalidEmailCallback = true;
}
let emailFlow: EmailAcceptance | undefined;
const emailStatus = document.querySelector<HTMLElement>("#email-status")!;
const changePassword = document.querySelector<HTMLButtonElement>(
  "#change-password",
)!;
const emailStorageKey = "harbor-email-acceptance";
type EmailSetup = {
  config: ReturnType<typeof parsePublicConfig>;
  fixture?: EmailFixture;
};
function saveEmailSetup(setup: EmailSetup) {
  // Only public configuration and fixture identity/stage survive navigation.
  sessionStorage.setItem(emailStorageKey, JSON.stringify(setup));
}
function readEmailSetup(): EmailSetup {
  const setup = JSON.parse(sessionStorage.getItem(emailStorageKey) ?? "null");
  if (
    !setup || !setup.fixture || typeof setup.fixture.id !== "string" ||
    typeof setup.fixture.email !== "string" ||
    !["signup", "confirmed", "recovery", "complete"].includes(
      setup.fixture.phase,
    )
  ) throw Error("Start the email test in this browser tab.");
  return { config: parsePublicConfig(setup.config), fixture: setup.fixture };
}
function emailClient(config: ReturnType<typeof parsePublicConfig>) {
  return createClient(config.supabaseUrl, config.publishableKey, {
    auth: {
      persistSession: false,
      detectSessionInUrl: false,
      autoRefreshToken: true,
      flowType: "implicit",
    },
  });
}
async function renderReceipts() {
  const output = document.querySelector("#receipts")!;
  try {
    output.textContent = JSON.stringify(await listReceipts(), null, 2);
  } catch {
    output.textContent =
      "Local receipts unavailable. Delivery remains unverified.";
  }
}
void renderReceipts();
navigator.serviceWorker.addEventListener("message", (event) => {
  if (event.data?.type === "receipts-changed") void renderReceipts();
});
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
    emailFlow = undefined;
    changePassword.hidden = true;
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
      site.workerUrl,
      { type: "module", scope: site.scope },
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

async function emailAction(work: () => Promise<void>) {
  await action(async () => {
    try {
      await work();
    } catch {
      emailStatus.textContent =
        "Email test failed or link was rejected. Confirmation/recovery remains unverified. Check the development configuration and use this same browser tab; do not share email links or passwords.";
    }
  });
}
document.querySelector("#signup")!.addEventListener("click", () => {
  void emailAction(async () => {
    if (
      !input("owned-inbox").checked || !input("email").checkValidity() ||
      !input("password").value
    ) throw Error("Use an owned, unused test inbox and password.");
    if (sessionStorage.getItem(emailStorageKey)) {
      throw Error("Finish or clean up the existing fixture first.");
    }
    const config = parsePublicConfig({
      supabaseUrl: input("url").value,
      publishableKey: input("key").value,
      vapidPublicKey: input("vapid").value,
    });
    saveEmailSetup({ config }); // Verify storage availability before creating an account.
    deps = undefined;
    emailFlow = undefined;
    changePassword.hidden = true;
    if (client) await client.auth.signOut({ scope: "local" });
    client = emailClient(config);
    const password = input("password").value;
    input("password").value = "";
    try {
      const fixture = await beginEmailFixture(
        client.auth,
        input("email").value.trim(),
        password,
        site.pageUrl,
      );
      saveEmailSetup({ config, fixture });
      emailStatus.textContent =
        "Signup request accepted. Email delivery is not yet verified. Open the confirmation link on this same device in this same tab, then return here. Keep the account for the recovery test and cleanup.";
    } catch {
      sessionStorage.removeItem(emailStorageKey);
      throw Error("Signup failed.");
    }
  });
});
document.querySelector("#request-recovery")!.addEventListener("click", () => {
  void emailAction(async () => {
    if (!client) throw Error("Confirm and sign in first.");
    const setup = readEmailSetup();
    const fixture = await requestEmailRecovery(
      client.auth,
      setup.fixture!,
      site.pageUrl,
    );
    saveEmailSetup({ ...setup, fixture });
    emailFlow = undefined;
    changePassword.hidden = true;
    deps = undefined;
    await client.auth.signOut({ scope: "local" });
    client = undefined;
    emailStatus.textContent =
      "Recovery request accepted; delivery remains unverified. Copy the original, unclicked Reset Password link from your email into Recovery email link below, then click Verify recovery email. Do not open the link first or share it in chat.";
  });
});
document.querySelector("#verify-recovery")!.addEventListener("click", () => {
  void emailAction(async () => {
    const link = input("recovery-link").value.trim();
    input("recovery-link").value = "";
    const setup = readEmailSetup();
    emailFlow = undefined;
    changePassword.hidden = true;
    deps = undefined;
    if (client) await client.auth.signOut({ scope: "local" });
    client = emailClient(setup.config);
    const next = new EmailAcceptance(client.auth, setup.fixture!, site.pageUrl);
    await next.acceptRecoveryLink(link);
    emailFlow = next;
    changePassword.hidden = false;
    emailStatus.textContent =
      "Recovery one-time token verified by the server for this exact test account. Enter and confirm your new password, then click Save new password.";
  });
});
changePassword.addEventListener("click", () => {
  void emailAction(async () => {
    const password = input("new-password").value;
    const confirmation = input("confirm-password").value;
    input("new-password").value = "";
    input("confirm-password").value = "";
    if (!emailFlow || !password || password !== confirmation) {
      throw Error("Enter matching new passwords after recovery.");
    }
    await emailFlow.changePassword(password);
    const setup = readEmailSetup();
    saveEmailSetup({
      ...setup,
      fixture: { ...setup.fixture!, phase: "complete" },
    });
    emailFlow = undefined;
    changePassword.hidden = true;
    client = undefined;
    deps = undefined;
    emailStatus.textContent =
      "Password update accepted and page session signed out. Verify the old password fails and the new password signs in, then request disposable account cleanup. Recovery acceptance is not complete until those checks pass.";
  });
});
if (invalidEmailCallback) {
  emailStatus.textContent =
    "Email link was rejected and removed from the address bar. Confirmation/recovery remains unverified.";
}
if (emailCallback) {
  const callback = emailCallback;
  emailCallback = null;
  void emailAction(async () => {
    const setup = readEmailSetup();
    input("url").value = setup.config.supabaseUrl;
    input("key").value = setup.config.publishableKey;
    input("vapid").value = setup.config.vapidPublicKey;
    input("email").value = setup.fixture!.email;
    client = emailClient(setup.config);
    const next = new EmailAcceptance(client.auth, setup.fixture!, site.pageUrl);
    await next.accept(callback);
    saveEmailSetup({
      ...setup,
      fixture: { ...setup.fixture!, phase: "confirmed" },
    });
    emailStatus.textContent =
      "Email confirmation verified with the server for this exact test account. Request the recovery email next.";
  });
}
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
