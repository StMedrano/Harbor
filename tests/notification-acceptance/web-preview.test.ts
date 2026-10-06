import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  acceptanceSite,
  previewOrigin,
  requirePreviewBuild,
} from "../../tools/notification-acceptance/web/site.ts";
import {
  beginEmailFixture,
  EmailAcceptance,
  type EmailAuth,
  requestEmailRecovery,
  takeEmailCallback,
} from "../../tools/notification-acceptance/web/email-auth.ts";
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.105.0";

const page = previewOrigin + "/acceptance/";
Deno.test("preview recipient uses only approved HTTPS host and isolated worker scope", () => {
  assertEquals(acceptanceSite(page), {
    pageUrl: page,
    workerUrl: page + "service-worker.js",
    scope: "/acceptance/",
  });
  assertEquals(
    acceptanceSite(page + "#access_token=private"),
    acceptanceSite(page),
  );
  assertEquals(acceptanceSite("http://localhost:3000/").scope, "/");
});
Deno.test("production aliases, arbitrary previews, HTTP and alternate paths cannot initialize recipient", () => {
  for (
    const url of [
      "https://harbor-lyart-nu.vercel.app/acceptance/",
      previewOrigin.replace("https:", "http:") + "/acceptance/",
      "https://other.vercel.app/acceptance/",
      previewOrigin + "/",
      previewOrigin + "/acceptance/extra",
      "http://localhost:3000/acceptance/",
    ]
  ) {
    assertThrows(() => acceptanceSite(url));
  }
});
Deno.test("development acceptance artifact refuses production and unknown build environments", () => {
  requirePreviewBuild("preview");
  for (const value of ["production", "development", "", undefined]) {
    assertThrows(() => requirePreviewBuild(value));
  }
});
Deno.test("preview confirmation callback scrubs credentials to exact acceptance page", () => {
  let clean = "";
  assertEquals(
    takeEmailCallback(
      page + "#type=signup&access_token=a&refresh_token=b",
      (url) => {
        clean = url;
      },
      page,
    )?.type,
    "signup",
  );
  assertEquals(clean, page);
});
Deno.test("signup and recovery requests use the reviewed preview callback and retain exact fixture", async () => {
  const fixture = {
    id: "fixture-user",
    email: "fixture@example.test",
    phase: "confirmed" as const,
  };
  const auth = {
    signUp: async (request: { options: { emailRedirectTo: string } }) => {
      assertEquals(request.options.emailRedirectTo, page);
      return {
        data: { user: { id: fixture.id, identities: [{}] } },
        error: null,
      };
    },
    getUser: async () => ({
      data: { user: { ...fixture, email_confirmed_at: "2026-10-05" } },
      error: null,
    }),
    resetPasswordForEmail: async (
      _email: string,
      options: { redirectTo: string },
    ) => {
      assertEquals(options.redirectTo, page);
      return { error: null };
    },
  } as unknown as SupabaseClient["auth"];
  assertEquals(
    (await beginEmailFixture(auth, fixture.email, "private", page)).id,
    fixture.id,
  );
  assertEquals(
    (await requestEmailRecovery(auth, fixture, page)).phase,
    "recovery",
  );
});
Deno.test("preview recovery verifies one-time action with exact approved redirect", async () => {
  const fixture = {
    id: "fixture-user",
    email: "fixture@example.test",
    phase: "recovery" as const,
  };
  const auth = {
    verifyOtp: async (request: { type: string; token_hash: string }) => {
      assertEquals(request.type, "recovery");
      assertEquals(request.token_hash, "private-hash");
      return { error: null };
    },
    getUser: async () => ({
      data: { user: { ...fixture, email_confirmed_at: "2026-10-05" } },
      error: null,
    }),
    signOut: async () => ({ error: null }),
  } as unknown as EmailAuth;
  assertEquals(
    await new EmailAcceptance(auth, fixture, page).acceptRecoveryLink(
      "https://bfvybxkjxilntjgndsrm.supabase.co/auth/v1/verify?token=private-hash&type=recovery&redirect_to=" +
        encodeURIComponent(page),
    ),
    "recovery",
  );
});
