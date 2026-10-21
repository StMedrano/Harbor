import { createDecideAccessHandler } from "../_shared/access-requests.ts";
import { defaultAccessDependencies } from "../_shared/access-requests-defaults.ts";
import { serveHarbor } from "../_shared/http.ts";
if (import.meta.main) {
  serveHarbor(createDecideAccessHandler(defaultAccessDependencies));
}
