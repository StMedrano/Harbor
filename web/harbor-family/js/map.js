/**
 * Map hook. The page CSP allows no map-tile hosts, so this draws a simple
 * locator card (no third-party requests) and links out to the system maps app.
 * `child.location` = { lat, lng, place, accuracyM, updatedAt } or null.
 */
const NS = 'http://www.w3.org/2000/svg';
const svg = (name, attrs = {}) => { const n = document.createElementNS(NS, name); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v)); return n; };

export function mapsUrl(L) {
  const lat = Number(L.lat).toFixed(6), lng = Number(L.lng).toFixed(6);
  return `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=17/${lat}/${lng}`;
}

export function mountMap(el, child) {
  const L = child.location;
  el.textContent = '';
  if (!L || !Number.isFinite(Number(L.lat)) || !Number.isFinite(Number(L.lng))) {
    el.textContent = 'Map appears once this child\u2019s device reports its location.';
    return;
  }
  const art = svg('svg', { viewBox: '0 0 200 120', width: '100%', height: '150', role: 'img', 'aria-label': 'Location marker' });
  for (let x = 20; x < 200; x += 20) art.append(svg('line', { x1: x, y1: 0, x2: x, y2: 120, stroke: 'currentColor', 'stroke-opacity': '.12' }));
  for (let y = 20; y < 120; y += 20) art.append(svg('line', { x1: 0, y1: y, x2: 200, y2: y, stroke: 'currentColor', 'stroke-opacity': '.12' }));
  const acc = Number(L.accuracyM);
  const ring = Number.isFinite(acc) ? Math.max(8, Math.min(50, 8 + Math.log10(Math.max(acc, 1)) * 14)) : 8;
  art.append(svg('circle', { cx: 100, cy: 60, r: ring, fill: 'currentColor', 'fill-opacity': '.12', stroke: 'currentColor', 'stroke-opacity': '.35' }));
  art.append(svg('circle', { cx: 100, cy: 60, r: 6, fill: child.color && /^#[0-9a-f]{3,8}$/i.test(child.color) ? child.color : '#3a6fb0', stroke: '#fff', 'stroke-width': 2 }));
  const coords = document.createElement('div');
  coords.className = 'sm';
  coords.textContent = `${Number(L.lat).toFixed(5)}, ${Number(L.lng).toFixed(5)}` + (Number.isFinite(acc) ? ` \u00b1 ${acc < 1000 ? Math.round(acc) + ' m' : (acc / 1000).toFixed(1) + ' km'}` : '');
  const link = document.createElement('a');
  link.href = mapsUrl(L); link.target = '_blank'; link.rel = 'noopener noreferrer';
  link.textContent = 'Open in Maps';
  link.className = 'maplink';
  el.append(art, coords, link);
}
