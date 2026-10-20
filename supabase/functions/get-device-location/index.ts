import { createGetLocationHandler } from "../_shared/location.ts";
import { defaultLocationDependencies } from "../_shared/location-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createGetLocationHandler(defaultLocationDependencies));
}
