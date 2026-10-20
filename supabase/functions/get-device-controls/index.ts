import { createGetDeviceControlsHandler } from "../_shared/activity.ts";
import { defaultActivityDependencies } from "../_shared/activity-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createGetDeviceControlsHandler(defaultActivityDependencies));
}
