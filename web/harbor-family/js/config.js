// Supabase settings come from Vite env vars (copy .env.example to .env.local).
// The anon/publishable key is safe in the browser: Row Level Security is what protects the data.
const env = (typeof import.meta !== 'undefined' && import.meta.env) || {};
export const SUPABASE_URL = env.VITE_SUPABASE_URL || '';
export const SUPABASE_ANON_KEY = env.VITE_SUPABASE_ANON_KEY || '';
export const SUPABASE_ENABLED = !!(SUPABASE_URL && SUPABASE_ANON_KEY);
