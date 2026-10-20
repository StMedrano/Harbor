// Read-only mapping and rendering of a parent's location reply (LocationReadReplyV1). Pure: no DOM, no network.
// Places come from the child's phone; a place older than 30 minutes since the last upload is "last known" and grey.
// Nothing here offers a control: there are no geofences, check-ins or alerts in this view.

export const STALE_AFTER_MS = 30 * 60 * 1000;
export const TILE = 256;
export const ZOOM = 15;
export const TILE_HOST = 'https://tile.openstreetmap.org';
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const isTime = s => typeof s === 'string' && Number.isFinite(Date.parse(s));
const isLat = n => typeof n === 'number' && Number.isFinite(n) && n >= -90 && n <= 90;
const isLng = n => typeof n === 'number' && Number.isFinite(n) && n >= -180 && n <= 180;
const STATUSES = ['sharing', 'permission_lost', 'background_missing', 'services_off'];

export const STATUS_TEXT = {
  permission_lost: 'Location permission was turned off on this phone, so no new places are arriving.',
  services_off: 'Location is switched off on this phone, so no new places are arriving.',
  background_missing: 'This phone only shares while Harbor is open, so updates may have gaps.',
};

const UNAVAILABLE = { status: 'unavailable' };

/** reply: LocationReadReplyV1 | null (null = location is not available on this surface). */
export function toLocationView(reply, nowMs, { offline = false } = {}) {
  if (!reply || typeof reply !== 'object') return { ...UNAVAILABLE };
  if (reply.state === 'none' || reply.state === 'expired') return { status: reply.state };
  if (reply.state !== 'available' || !Array.isArray(reply.fixes)) return { ...UNAVAILABLE };
  const fixes = reply.fixes
    .filter(f => f && isTime(f.at) && isLat(f.latitude) && isLng(f.longitude))
    .map(f => ({ at: f.at, lat: f.latitude, lng: f.longitude, accuracyM: typeof f.accuracyM === 'number' && f.accuracyM >= 0 ? f.accuracyM : null }))
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
  const receivedAt = isTime(reply.receivedAt) ? reply.receivedAt : null;
  return {
    status: 'available', offline,
    sharing: STATUSES.includes(reply.status) ? reply.status : null,
    receivedAt, observedAt: isTime(reply.observedAt) ? reply.observedAt : null,
    stale: !receivedAt || nowMs - Date.parse(receivedAt) > STALE_AFTER_MS,
    latest: fixes[0] || null,
    fixes,
  };
}

/** Web Mercator world pixel for a coordinate at a zoom level. */
export function project(lat, lng, z = ZOOM) {
  const w = TILE * 2 ** z, s = Math.sin(lat * Math.PI / 180);
  const clamped = Math.min(Math.max(s, -0.9999), 0.9999);
  return { x: (lng + 180) / 360 * w, y: (0.5 - Math.log((1 + clamped) / (1 - clamped)) / (4 * Math.PI)) * w };
}

/** Tiles covering a halfW x halfH pixel window around a centre, each offset from the window centre. */
export function tileGrid(lat, lng, { z = ZOOM, halfW = 400, halfH = 140 } = {}) {
  const c = project(lat, lng, z), n = 2 ** z, tiles = [];
  for (let ty = Math.floor((c.y - halfH) / TILE); ty <= Math.floor((c.y + halfH) / TILE); ty++) {
    if (ty < 0 || ty >= n) continue;
    for (let tx = Math.floor((c.x - halfW) / TILE); tx <= Math.floor((c.x + halfW) / TILE); tx++) {
      const wx = ((tx % n) + n) % n;
      tiles.push({ url: `${TILE_HOST}/${z}/${wx}/${ty}.png`, dx: Math.round(tx * TILE - c.x), dy: Math.round(ty * TILE - c.y) });
    }
  }
  return { centre: c, tiles };
}

const stamp = iso => `<time datetime="${esc(iso)}">${esc(new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }))}</time>`;
const note = (cls, html) => `<div class="note ${cls}" role="status">${html}</div>`;
const pos = (dx, dy) => `left:calc(50% + ${dx}px);top:calc(50% + ${dy}px)`;

/** The map: OpenStreetMap tiles, older places as small dots, the newest place as the marker (grey when last known). */
export function renderMap(v, { color = '#1f6b5c', initial = '' } = {}) {
  if (v.status !== 'available' || !v.latest) return '';
  const { latest } = v, { centre, tiles } = tileGrid(latest.lat, latest.lng);
  const trail = v.fixes.slice(1, 50).map(f => { const p = project(f.lat, f.lng); return { dx: Math.round(p.x - centre.x), dy: Math.round(p.y - centre.y) }; })
    .filter(p => Math.abs(p.dx) < 400 && Math.abs(p.dy) < 140);
  const osm = `https://www.openstreetmap.org/?mlat=${latest.lat.toFixed(5)}&mlon=${latest.lng.toFixed(5)}#map=16/${latest.lat.toFixed(5)}/${latest.lng.toFixed(5)}`;
  return `<div class="mapbox live" id="map" role="img" aria-label="${v.stale ? 'Last known place' : 'Latest place'} on a map">`
    + tiles.map(t => `<img class="tile" alt="" src="${esc(t.url)}" width="${TILE}" height="${TILE}" referrerpolicy="strict-origin-when-cross-origin" loading="lazy" style="${pos(t.dx, t.dy)}">`).join('')
    + trail.map(p => `<i class="trail" style="${pos(p.dx, p.dy)}"></i>`).join('')
    + `<span class="pin${v.stale ? ' old' : ''}" style="${pos(0, 0)};${v.stale ? '' : `background:${esc(color)}`}">${esc(initial)}</span>`
    + `<a class="osm" href="${esc(osm)}" target="_blank" rel="noopener noreferrer">© OpenStreetMap contributors</a></div>`;
}

export function renderLocationView(v, { name = 'your child', color, initial } = {}) {
  const who = esc(name);
  if (v.status === 'unavailable') return `<div class="mapbox" id="map">Location sharing is not available here.</div><div class="empty"><h2>No location here</h2><p class="sm">Location is shared by the Harbor Family Android app on the child’s phone.</p></div>`;
  if (v.status === 'none') return `<div class="mapbox" id="map">No place reported yet.</div><div class="empty"><h2>No location for ${who} yet</h2><p class="sm">Sharing starts once location is allowed in Harbor Family on ${who}’s phone. Places arrive every 5 to 15 minutes, not live.</p></div>`;
  if (v.status === 'expired') return `<div class="mapbox" id="map">No recent place.</div><div class="empty"><h2>The last place expired</h2><p class="sm">Places older than 30 days are removed. Nothing new has arrived from ${who}’s phone.</p></div>`;
  const flags = (v.offline ? note('w', '<b>Can’t reach Harbor.</b> Showing the last place received.') : '')
    + (v.stale && v.latest ? note('w', `<b>Last known place.</b> Nothing new has arrived for more than 30 minutes. ${who}’s phone may be still, offline or restricted by Android.`) : '')
    + (v.sharing && STATUS_TEXT[v.sharing] ? note('w', esc(STATUS_TEXT[v.sharing])) : '');
  if (!v.latest) return `<div class="mapbox" id="map">No place reported yet.</div>${flags}<div class="empty"><h2>No place yet</h2><p class="sm">${who}’s phone is connected but hasn’t sent a place.</p></div>`;
  const acc = v.latest.accuracyM !== null ? ` · within about ${Math.round(v.latest.accuracyM)} m` : '';
  return renderMap(v, { color, initial })
    + `<div class="lh"><div><h2>${v.stale ? 'Last known place' : 'Latest place'}</h2><div class="muted sm">Seen ${stamp(v.latest.at)}${esc(acc)}${v.receivedAt ? ` · received ${stamp(v.receivedAt)}` : ''}</div></div></div>`
    + flags
    + `<p class="rs">Places come from ${who}’s phone every 5 to 15 minutes, so this is not live tracking. Up to 30 days are kept; dots show earlier places.</p>`;
}
