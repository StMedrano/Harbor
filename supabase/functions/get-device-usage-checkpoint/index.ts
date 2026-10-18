import { createUsageCheckpointHandler } from "../_shared/usage.ts";
import { defaultUsageDependencies } from "../_shared/usage-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createUsageCheckpointHandler(defaultUsageDependencies));
}
