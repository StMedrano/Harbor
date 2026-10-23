/**
 * HARBOR FAMILY DATA LAYER — the only module the UI imports for data. It is the Supabase implementation
 * (supabase-api.js) when VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY are set at build time. Without them every
 * call fails with ApiError('unconfigured'); there is no stand-in data.
 */
import { ApiError } from './errors.js';
import { SUPABASE_ENABLED } from './config.js';
import { supabaseApi } from './supabase-api.js';
export { ApiError };

const unconfigured = new Proxy({}, {
  get: (_, name) => name === 'getSession' ? async () => null
    : async () => { throw new ApiError('unconfigured', 'Harbor isn’t connected on this site yet.'); },
});

export const api = SUPABASE_ENABLED ? supabaseApi : unconfigured;
