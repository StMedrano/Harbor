import { createRequestAccessHandler } from "../_shared/access-requests.ts";
import { defaultAccessDependencies } from "../_shared/access-requests-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  // Pushes queued by this request (and any others due) are delivered right after it answers.
  const { withPushDelivery } = await import("../_shared/push-now.ts");
  serveHarbor(withPushDelivery(createRequestAccessHandler(defaultAccessDependencies)));
}
