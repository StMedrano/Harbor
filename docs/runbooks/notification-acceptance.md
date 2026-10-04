# Development notification acceptance

The browser recipient exists in draft PR #15. Android and the secure fixture/operator runner are still pending. Automated lifecycle/route checks do not prove hosted delivery. Do not use a real family's credentials or records for this disposable acceptance workflow.

From repository root, with Deno 2.9.7 on the command path:

```sh
deno test --frozen tests/notification-acceptance
deno check --frozen tools/notification-acceptance/web/app.ts tools/notification-acceptance/web/service-worker.ts
deno run --frozen --allow-read --allow-write --allow-run=deno tools/notification-acceptance/web/build.ts
deno run --frozen --allow-read=tools/notification-acceptance/web/dist --allow-net=localhost:3000 tools/notification-acceptance/web/serve.ts
```

On the current Windows workspace, replace `deno` with `../deno-runtime/deno.exe` and the build permission with `--allow-run=../deno-runtime/deno.exe`.

Open `http://localhost:3000/`. Enter the development publishable key and the **existing public** VAPID key matching the deployed `VAPID_PRIVATE_KEY`. Never enter server, worker or private VAPID keys. The only accepted backend is `https://bfvybxkjxilntjgndsrm.supabase.co`.

Sign in using the disposable parent handoff created by the operator workflow once it exists. Click Enable notifications and explicitly allow the browser prompt. Backend success confirms registration only. Local receipt evidence is stored separately and survives reload; sign-in does not survive reload.

The deployed `HARBOR_ALLOWED_ORIGINS` must include exact `http://localhost:3000`. Inspect its existing value in the Supabase dashboard and append this origin using the backend's configured list syntax, preserving the existing Vercel origin. Do not overwrite the list with localhost alone. Secret-name inventory cannot verify its contents.

Click Remove registration while signed in to remove backend registration before unsubscribing locally. If removal fails, retry before discarding the browser profile. A reload requires sign-in again. Avoid switching test parents on the same installation until removal succeeds.

Current live verification blocker: matching public VAPID input, disposable parent handoff and confirmed localhost origin are not available to this browser session. The browser page renders and rejects unauthenticated registration; actual permission, hosted registration, worker delivery and combined Android receipt have not been verified. Supply configuration through this local page or a restricted operator handoff, never by posting private credentials in chat.

No toolkit deployment, schema change, production client or product UI change is needed. Remaining fixture preparation, dispatch validation, fresh-MFA revocation and exhaustive cleanup must be implemented under Tasks 3–6 of the approved plan before claiming complete end-to-end acceptance.
