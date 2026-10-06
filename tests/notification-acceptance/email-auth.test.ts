import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  beginEmailFixture,
  confirmEmailAfterSignIn,
  EmailAcceptance,
  type EmailAuth,
  type EmailFixture,
  requestEmailRecovery,
  takeEmailCallback,
} from "../../tools/notification-acceptance/web/email-auth.ts";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.105.0";

const fixture: EmailFixture = {
  id: "fixture-user",
  email: "fixture@example.test",
  phase: "signup",
};
const callback = {
  type: "signup" as const,
  access_token: "private-access",
  refresh_token: "private-refresh",
};
const recoveryLink =
  "https://bfvybxkjxilntjgndsrm.supabase.co/auth/v1/verify?token=private-recovery-hash&type=recovery&redirect_to=http%3A%2F%2Flocalhost%3A3000%2F";
function authFixture() {
  const state = {
    signedOut: 0,
    updated: 0,
    rejected: false,
    user: {
      id: fixture.id,
      email: fixture.email,
      email_confirmed_at: "2026-10-05",
      is_anonymous: false,
    },
  };
  const auth = {
    verifyOtp: async (request: { token_hash: string; type: string }) => ({
      error: state.rejected || request.token_hash !== "private-recovery-hash" ||
          request.type !== "recovery"
        ? Error("invalid recovery token")
        : null,
    }),
    setSession: async () => ({
      error: state.rejected ? Error("expired") : null,
    }),
    getUser: async () => ({ data: { user: state.user }, error: null }),
    signOut: async () => {
      state.signedOut++;
      return { error: null };
    },
    updateUser: async () => {
      state.updated++;
      return {
        data: { user: state.user },
        error: state.rejected ? Error("failure") : null,
      };
    },
  } as unknown as EmailAuth;
  return { auth, state };
}

Deno.test("callback credentials are removed from history before parsing and never retained in clean URL", () => {
  let clean = "";
  const result = takeEmailCallback(
    "http://localhost:3000/#type=signup&access_token=private-access&refresh_token=private-refresh",
    (url) => {
      clean = url;
    },
  );
  assertEquals(clean, "http://localhost:3000/");
  assertEquals(result, callback);
});
Deno.test("failed, incomplete, duplicate and unsupported callbacks scrub URL and fail closed", () => {
  for (
    const fragment of [
      "error=access_denied&error_description=secret",
      "type=recovery&access_token=a",
      "type=magiclink&access_token=a&refresh_token=b",
      "type=signup&type=recovery&access_token=a&refresh_token=b",
    ]
  ) {
    let clean = "";
    assertThrows(() =>
      takeEmailCallback(`http://localhost:3000/#${fragment}`, (url) => {
        clean = url;
      })
    );
    assertEquals(clean, "http://localhost:3000/");
  }
  assertEquals(
    takeEmailCallback("http://localhost:3000/", () => {
      throw Error("not a callback");
    }),
    null,
  );
});
Deno.test("confirmation requires server verified exact fixture and does not unlock recovery", async () => {
  const { auth, state } = authFixture();
  const flow = new EmailAcceptance(auth, fixture);
  assertEquals(await flow.accept(callback), "confirmed");
  await assertRejects(() => flow.changePassword("new-password"));
  assertEquals(state.updated, 0);
});
Deno.test("wrong identity, unconfirmed email, anonymous identity and rejected sessions sign out without acceptance", async () => {
  for (
    const changes of [{ id: "other" }, { email: "other@example.test" }, {
      email_confirmed_at: "",
    }, { is_anonymous: true }]
  ) {
    const { auth, state } = authFixture();
    Object.assign(state.user, changes);
    await assertRejects(() =>
      new EmailAcceptance(auth, fixture).accept(callback)
    );
    assertEquals(state.signedOut, 1);
    assertEquals(state.updated, 0);
  }
  const { auth, state } = authFixture();
  state.rejected = true;
  await assertRejects(() =>
    new EmailAcceptance(auth, fixture).accept(callback)
  );
  assertEquals(state.signedOut, 1);
});
Deno.test("unsolicited recovery and signup callbacks in recovery phase cannot change passwords", async () => {
  for (
    const [phase, type] of [["confirmed", "recovery"], [
      "recovery",
      "signup",
    ]] as const
  ) {
    const { auth, state } = authFixture();
    const flow = new EmailAcceptance(auth, { ...fixture, phase });
    await assertRejects(() => flow.accept({ ...callback, type }));
    await assertRejects(() => flow.changePassword("new-password"));
    assertEquals(state.updated, 0);
  }
});
Deno.test("recovery rechecks identity, retains retry after failure and consumes gate after success", async () => {
  const { auth, state } = authFixture();
  const flow = new EmailAcceptance(auth, { ...fixture, phase: "recovery" });
  assertEquals(
    await flow.acceptRecoveryLink(recoveryLink),
    "recovery",
  );
  state.rejected = true;
  await assertRejects(() => flow.changePassword("new-password"));
  state.rejected = false;
  await flow.changePassword("new-password");
  await assertRejects(() => flow.changePassword("another-password"));
  assertEquals(state.updated, 2);
  assertEquals(state.signedOut, 1);
});
Deno.test("identity changing after recovery callback prevents password update and clears session", async () => {
  const { auth, state } = authFixture();
  const flow = new EmailAcceptance(auth, { ...fixture, phase: "recovery" });
  await flow.acceptRecoveryLink(recoveryLink);
  state.user.id = "other";
  await assertRejects(() => flow.changePassword("new-password"));
  assertEquals(state.updated, 0);
  assertEquals(state.signedOut, 1);
});

Deno.test("an ordinary fixture session relabeled recovery cannot unlock password changes", async () => {
  const { auth, state } = authFixture();
  const flow = new EmailAcceptance(auth, { ...fixture, phase: "recovery" });
  await assertRejects(() => flow.accept({ ...callback, type: "recovery" }));
  await assertRejects(() => flow.changePassword("new-password"));
  assertEquals(state.updated, 0);
});
Deno.test("foreign, expired, used, duplicate and signup recovery links fail closed", async () => {
  for (
    const link of [
      recoveryLink.replace("bfvybxkjxilntjgndsrm", "other"),
      recoveryLink.replace("private-recovery-hash", "expired"),
      recoveryLink.replace("type=recovery", "type=signup"),
      recoveryLink + "&token=duplicate",
    ]
  ) {
    const { auth, state } = authFixture();
    const flow = new EmailAcceptance(auth, { ...fixture, phase: "recovery" });
    await assertRejects(() => flow.acceptRecoveryLink(link));
    await assertRejects(() => flow.changePassword("new-password"));
    assertEquals(state.updated, 0);
  }
});

Deno.test("signup records only email and returned identity with exact local callback, never the password", async () => {
  let redirect = "";
  const auth = {
    signUp: async (request: { options: { emailRedirectTo: string } }) => {
      redirect = request.options.emailRedirectTo;
      return {
        data: { user: { id: fixture.id, identities: [{}] }, session: null },
        error: null,
      };
    },
  } as unknown as SupabaseClient["auth"];
  assertEquals(
    await beginEmailFixture(auth, fixture.email, "private-password"),
    fixture,
  );
  assertEquals(redirect, "http://localhost:3000/");
});
Deno.test("signup denial and existing-account obfuscation never become an accepted fixture", async () => {
  for (
    const data of [{ user: null, session: null }, {
      user: { id: "existing", identities: [] },
      session: null,
    }]
  ) {
    const auth = {
      signUp: async () => ({ data, error: null }),
    } as unknown as SupabaseClient["auth"];
    await assertRejects(() =>
      beginEmailFixture(auth, fixture.email, "private-password")
    );
  }
});
Deno.test("only confirmed exact fixture may request recovery; delivery remains unverified", async () => {
  const { auth } = authFixture();
  let sent = 0;
  const full = Object.assign(auth, {
    resetPasswordForEmail: async (
      _email: string,
      options: { redirectTo: string },
    ) => {
      assertEquals(_email, fixture.email);
      assertEquals(options.redirectTo, "http://localhost:3000/");
      sent++;
      return { error: null };
    },
  }) as SupabaseClient["auth"];
  await assertRejects(() => requestEmailRecovery(full, fixture));
  assertEquals(sent, 0);
  assertEquals(
    await requestEmailRecovery(full, { ...fixture, phase: "confirmed" }),
    { ...fixture, phase: "recovery" },
  );
});

Deno.test("confirmed signup fixture can resume after consumed callback without reusing the email link", async () => {
  const { auth, state } = authFixture();
  const resumed = await confirmEmailAfterSignIn(auth, fixture);
  assertEquals(resumed, { ...fixture, phase: "confirmed" });
  assertEquals(fixture.phase, "signup");
  const flow = new EmailAcceptance(auth, resumed);
  await assertRejects(() => flow.changePassword("new-password"));
  assertEquals(state.updated, 0);
});
Deno.test("sign-in confirmation resume rejects wrong or unconfirmed fixture and preserves recovery stages", async () => {
  for (const changes of [{ id: "other" }, { email_confirmed_at: "" }]) {
    const { auth, state } = authFixture();
    Object.assign(state.user, changes);
    await assertRejects(() => confirmEmailAfterSignIn(auth, fixture));
    assertEquals(state.signedOut, 1);
  }
  for (const phase of ["recovery", "complete"] as const) {
    const { auth } = authFixture();
    await assertRejects(() =>
      confirmEmailAfterSignIn(auth, { ...fixture, phase })
    );
  }
});
