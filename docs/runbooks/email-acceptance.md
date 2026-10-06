# Development email acceptance

This bounded check extends the existing local notification recipient. It does
not establish production email readiness or implement either parent product.
Use only the reviewed development project and an explicitly authorized owned
inbox with no existing development Auth user. The operator must verify that
absence before starting. Never change a pre-existing account for this test.

## Preparation

Verify hosted email/password signup is enabled, email auto-confirm is disabled,
and the reviewed callback is `http://localhost:3000/`. Preserve other Auth
settings, templates, production deployments and Android enrollment. Build and
serve the existing web recipient as described in notification-acceptance.md.
Use public configuration only; never place administrative or worker keys here.

The user enters passwords and completes password changes themselves. Never ask
for passwords, confirmation/recovery links or session tokens in chat. Keep the
same browser tab through the test; another browser/profile does not retain the
pending fixture. Only public configuration, exact fixture identity/email and
stage are stored in sessionStorage. Passwords, recovery links and sessions stay
in memory. Existing notification receipt evidence is preserved.

## Live check

1. Fill public development configuration and the authorized test email. Enter a
   test password privately. Check the owned-inbox acknowledgment and click
   **Create email test account** once. API acceptance is not email delivery.
2. Read the confirmation email. On this computer, paste its confirmation link
   into this same tab's address bar. Callback credentials are scrubbed before
   client initialization. The page verifies the exact Auth user and confirmed
   email with the server. Save sanitized confirmation status; do not save tokens.
3. Click **Request recovery email**. API acceptance is not delivery. Read the
   recovery email and copy its original Reset Password link **without opening
   it**. Paste it into **Recovery email link** and click **Verify recovery email**.
   The page accepts only the exact development verification endpoint and local
   redirect, then calls `verifyOtp` with the explicit recovery action. A normal
   access token relabeled as recovery never unlocks password changes. Invalid,
   expired, reused or wrong-account recovery tokens remain unverified.
4. After recovery verification, enter and confirm a new password privately and
   click **Save new password**. The exact user is rechecked before the update;
   failed updates retain the verified session for retry. Successful updates
   consume the page gate and sign out locally.
5. Use **Sign in** to verify that the old password fails and the new password
   succeeds. Record these as separate checks. Do not claim completed recovery
   solely from the password update response.
6. The operator verifies the exact newly created Auth identity against the
   preflight and removes only this disposable account using the development
   Admin API after acceptance. Confirm absence independently, sign out/close
   this tab, and discard its pending fixture. Never delete unrelated users or
   active notification fixtures. Retain sanitized evidence only.

If the restricted development sender refuses the inbox, a rate limit applies,
or the callback/fixture cannot be verified, record the exact sanitized blocker
and stop. Do not bypass confirmation, generate a substitute administrative
link, or repeatedly send email to simulate live acceptance. Changing SMTP or
email templates requires its own reviewed scope.

## Automated evidence

Run the notification acceptance test directory, browser app type check and
bundle, then the required Foundation CI gates. Unit tests cover callback
scrubbing, exact server-verified identity, recovery stage/action proof and
failure/retry behavior; they do not establish real email delivery, link expiry
timing, successful password replacement or fixture cleanup. Those remain live
checks until observed.

## Protected online development recipient

The user approved publishing this existing tool under `/acceptance/` on the
existing branch preview, with Vercel sign-in protection retained. The reviewed
address is:
`https://harbor-git-feat-notification-accepta-83c20a-stalinvmedrano-1274.vercel.app/acceptance/`.

Use the same device, browser and tab for signup and confirmation. The online
page includes only public development configuration, so a phone does not need
access to the local computer or its private credentials. A callback opened on
another browser/profile cannot borrow the pending fixture.

The static preview build refuses any environment other than Vercel preview.
Its output includes only the unchanged demo index/bundle and three acceptance
assets. The worker scope is `/acceptance/`; notification clicks return there.
Deployment sign-in protection must remain enabled. Do not distribute bypass
tokens, promote this preview to production, or point a production configuration
at development Supabase.

Add only the exact acceptance callback to development Auth's redirect list,
preserving the Site URL and all other settings. For the two Web Push lifecycle
functions, `HARBOR_ACCEPTANCE_PREVIEW_ORIGIN` adds this one reviewed origin only
on the exact development backend. It preserves the existing
`HARBOR_ALLOWED_ORIGINS` setting without retrieving or replacing its value.
Unknown origins, production backends and actual unauthenticated requests remain
denied. Deploy only the relevant reviewed Web Push lifecycle functions after CI.

Online availability does not complete live email or notification acceptance.
The real user email flow, old/new-password checks and exact fixture cleanup
remain required. Production SMTP, parent client implementation and the wider
roadmap remain separate work.

If Supabase has already confirmed the email but the callback was consumed or
rejected, sign in with the original password in the same test tab. The page
rechecks the server-confirmed exact pending fixture before advancing signup to
confirmed. It does not reuse the email link, create another account, replace a
recovery/complete stage, or unlock password changes without the separately
verified recovery token. If the original pending fixture is absent, return to
the original tab; do not invent a new fixture binding.
