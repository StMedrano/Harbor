import { createClearAlertsHandler } from "../_shared/message-alerts.ts";
import { defaultAlertDependencies } from "../_shared/message-alerts-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createClearAlertsHandler(defaultAlertDependencies));
}
