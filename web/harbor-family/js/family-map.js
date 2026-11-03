/**
 * The family on one map: every child in the family, and every parent who is sharing right now.
 *
 * Children never choose — their phones report, which is the point of Harbor. A parent appears only while they
 * share, and when they do the whole family sees them, children and the other parents alike. A person with no place
 * yet is still listed, so nobody silently disappears from the family.
 */
import { project, tileGrid, TILE } from './location-view.js';

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
/**
 * Offsets are from the middle of the map, exactly as the single-child map places them (location-view.js): a tile's
 * corner, or a pin's centre once its own CSS margin pulls it back by half its size. A transform with no anchor puts
 * everything relative to the top-left corner instead, which is how this map first shipped.
 */
export const pos = (dx, dy) => `left:calc(50% + ${dx}px);top:calc(50% + ${dy}px)`;

/** Nothing new for this long and a place is shown as last known rather than current. */
export const STALE_AFTER_MS = 30 * 60 * 1000;
/** The map as it was first laid out on a wide screen; the page passes the size it really has. */
const WIDE = { width: 800, height: 280 };
/** Room around the outermost pins so a whole pin shows, not half of one. */
const MARGIN = 34;

const initialOf = name => (String(name || '?').trim()[0] || '?').toUpperCase();

/** One row of public.family_places as the map needs it. */
function person(row, userId, nowMs) {
  const lat = Number(row.latitude), lng = Number(row.longitude);
  const placed = row.latitude !== null && row.latitude !== undefined && Number.isFinite(lat) && Number.isFinite(lng);
  const at = row.fix_at ? Date.parse(row.fix_at) : NaN;
  const kind = row.kind === 'parent' ? 'parent' : 'child';
  return {
    id: String(row.person_id),
    kind,
    name: String(row.name ?? '').trim() || (kind === 'parent' ? 'A parent' : 'A child'),
    self: kind === 'parent' && !!userId && row.user_id === userId,
    at: row.fix_at || null,
    accuracyM: Number.isFinite(Number(row.accuracy_m)) ? Number(row.accuracy_m) : null,
    lat: placed ? lat : null,
    lng: placed ? lng : null,
    placed,
    stale: placed && Number.isFinite(at) ? nowMs - at > STALE_AFTER_MS : false,
  };
}

/** Children first, then parents, each by name, with you last so the others read first. */
function order(a, b) {
  if (a.kind !== b.kind) return a.kind === 'child' ? -1 : 1;
  if (a.self !== b.self) return a.self ? 1 : -1;
  return a.name.localeCompare(b.name);
}

export function toFamilyMap(rows, { userId = null, nowMs = Date.now() } = {}) {
  const people = (Array.isArray(rows) ? rows : []).map(r => person(r, userId, nowMs)).sort(order);
  const placed = people.filter(p => p.placed);
  const centre = centreOf(placed);
  return { people, placed, centre, zoom: fitZoom(placed, { ...WIDE, centre }) };
}

/** The middle of the box around everyone. An average drifts towards whoever is close together and crops the one far away. */
function centreOf(placed) {
  if (!placed.length) return null;
  const lats = placed.map(p => p.lat), lngs = placed.map(p => p.lng);
  return { lat: (Math.min(...lats) + Math.max(...lats)) / 2, lng: (Math.min(...lngs) + Math.max(...lngs)) / 2 };
}

/**
 * The closest zoom that still fits everyone in the window. A family can be a street apart or a county apart, so a
 * fixed zoom would either crop people out or show one pin in an empty ocean.
 */
export function fitZoom(placed, { width = WIDE.width, height = WIDE.height, centre = centreOf(placed), max = 16, min = 3 } = {}) {
  if (placed.length < 2) return 15;
  const roomX = Math.max(0, width / 2 - MARGIN), roomY = Math.max(0, height / 2 - MARGIN);
  for (let z = max; z > min; z--) {
    const middle = project(centre.lat, centre.lng, z);
    const fits = placed.every(p => {
      const point = project(p.lat, p.lng, z);
      return Math.abs(point.x - middle.x) <= roomX && Math.abs(point.y - middle.y) <= roomY;
    });
    if (fits) return z;
  }
  return min;
}

/** Where each person's pin sits relative to the map's centre, and whether it is inside the window at all. */
export function pinsFor(view, { width = WIDE.width, height = WIDE.height } = {}) {
  if (!view.centre) return [];
  const z = view.zoom, centre = project(view.centre.lat, view.centre.lng, z);
  return view.placed.map(p => {
    const point = project(p.lat, p.lng, z);
    const dx = Math.round(point.x - centre.x), dy = Math.round(point.y - centre.y);
    return { person: p, dx, dy, inside: Math.abs(dx) <= width / 2 && Math.abs(dy) <= height / 2 };
  });
}

/** width and height are the map's real size on this screen: a phone's map is far narrower than a desktop's. */
export function renderFamilyMap(view, { colorOf = () => 'var(--parent-pin)', width = WIDE.width, height = WIDE.height } = {}) {
  if (!view.centre) return '';
  const zoom = fitZoom(view.placed, { width, height, centre: view.centre });
  const { tiles } = tileGrid(view.centre.lat, view.centre.lng, { z: zoom, halfW: width / 2, halfH: height / 2 });
  const pins = pinsFor({ ...view, zoom }, { width, height }).filter(p => p.inside);
  const c = view.centre;
  const osm = `https://www.openstreetmap.org/#map=${zoom}/${c.lat.toFixed(5)}/${c.lng.toFixed(5)}`;
  return `<div class="hf-map" id="map" role="img" aria-label="Your family on a map">`
    + tiles.map(t => `<img class="tile" alt="" src="${esc(t.url)}" width="${TILE}" height="${TILE}" referrerpolicy="strict-origin-when-cross-origin" loading="lazy" style="${pos(t.dx, t.dy)}">`).join('')
    + pins.map(({ person: p, dx, dy }) =>
      `<span class="pin${p.stale ? ' old' : ''}" title="${esc(p.name)}" style="${pos(dx, dy)}${p.stale ? '' : `;background:${esc(colorOf(p))}`}">${esc(initialOf(p.name))}</span>`).join('')
    + `<a class="osm" href="${esc(osm)}" target="_blank" rel="noopener noreferrer">© OpenStreetMap contributors</a></div>`;
}

/** What to say under a person's name, without ever implying a place is live. */
export function statusOf(p, { ago = () => '' } = {}) {
  if (!p.placed) return p.kind === 'parent' ? 'Sharing, no place yet' : 'No place yet';
  const when = p.at ? ago(p.at) : '';
  const within = p.accuracyM !== null ? ` · within about ${Math.round(p.accuracyM)} m` : '';
  return `${p.stale ? 'Last known' : 'Seen'} ${when}${within}`;
}
