// Same-site forwarder from the Harbor Family web app (preview deployments of this project) to Harbor's functions on
// the development backend. The browser calls /api/fn/<name> on this site, so the backend's list of allowed web
// origins stays as it is and no cross-origin request is made. Only the listed functions, only POST, only the caller's
// own token and Harbor's request headers are passed on; nothing is stored or logged here. Production refuses every call.
//
// This is Edge Middleware rather than an api/ function on purpose: this project builds with its own build command and
// serves a static output directory, so Vercel does not pick up api/ here. Middleware is picked up either way.
export const config = { matcher: '/api/fn/:path*' };

const TARGET = 'https://bfvybxkjxilntjgndsrm.supabase.co/functions/v1/';
const FUNCTIONS = new Set([
  'create-family', 'create-child', 'create-device-pairing', 'create-parent-invite', 'accept-parent-invite', 'revoke-device',
  'get-device-usage', 'get-device-location', 'get-message-alerts', 'get-device-activity', 'get-device-checkins', 'request-device-checkin',
  'get-device-controls', 'update-device-state', 'get-access-requests', 'decide-access-request',
  'device-claim', 'device-sync',
]);
const PASS = ['authorization', 'apikey', 'content-type', 'x-harbor-device-id', 'x-harbor-timestamp', 'x-harbor-nonce', 'x-harbor-signature'];
const MAX_BYTES = 65_536;
const refuse = (status, code, message) => Response.json({ code, message }, { status, headers: { 'cache-control': 'no-store' } });

export default async function middleware(request) {
  if (process.env.VERCEL_ENV === 'production') return refuse(404, 'NOT_FOUND', 'Not found');
  // This site redirects to trailing slashes, so /api/fn/<name> can arrive as /api/fn/<name>/.
  const name = new URL(request.url).pathname.slice('/api/fn/'.length).replace(/\/$/, '');
  if (request.method !== 'POST' || !FUNCTIONS.has(name)) return refuse(404, 'NOT_FOUND', 'Not found');
  const body = await request.arrayBuffer();
  if (body.byteLength > MAX_BYTES) return refuse(413, 'VALIDATION_FAILED', 'Request is too large');
  const headers = new Headers();
  for (const h of PASS) { const v = request.headers.get(h); if (v) headers.set(h, v); }
  let reply;
  try { reply = await fetch(TARGET + name, { method: 'POST', headers, body }); }
  catch { return refuse(502, 'DEVICE_OFFLINE', 'Harbor could not be reached'); }
  return new Response(reply.body, { status: reply.status, headers: { 'content-type': reply.headers.get('content-type') || 'application/json', 'cache-control': 'no-store' } });
}
