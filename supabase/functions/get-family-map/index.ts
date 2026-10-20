import { createFamilyMapHandler } from "../_shared/familymap.ts";
import { defaultFamilyMapDependencies } from "../_shared/familymap-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createFamilyMapHandler(defaultFamilyMapDependencies));
}
