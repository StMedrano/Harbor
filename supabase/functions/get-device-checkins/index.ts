import { createReadCheckInsHandler } from "../_shared/checkin.ts";
import { defaultCheckInDependencies } from "../_shared/checkin-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createReadCheckInsHandler(defaultCheckInDependencies));
}
