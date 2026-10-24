// Supabase settings come from Vite env vars (copy .env.example to .env.local).
// The anon/publishable key is safe in the browser: Row Level Security is what protects the data.
const env = (typeof import.meta !== 'undefined' && import.meta.env) || {};
export const SUPABASE_URL = env.VITE_SUPABASE_URL || '';
export const SUPABASE_ANON_KEY = env.VITE_SUPABASE_ANON_KEY || '';
export const SUPABASE_ENABLED = !!(SUPABASE_URL && SUPABASE_ANON_KEY);

// The public VAPID key for browser notifications. A public key by design, so it belongs in the browser; the private
// one stays a backend secret. Without it the app offers no notifications rather than failing when a parent asks for
// them, so this stays optional.
export const VAPID_PUBLIC_KEY = String(env.VITE_VAPID_PUBLIC_KEY || '').trim();
// Where this build is served from, so the service worker registers at the app's own scope and not above it.
export const BASE_URL = env.BASE_URL || '/';
