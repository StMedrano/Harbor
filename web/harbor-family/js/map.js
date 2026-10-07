/**
 * Map hook. Replace this stub with Leaflet / MapLibre / Google Maps.
 * Called after every render of the Map tab. `el` is an empty container (#map).
 * `child.location` = { lat, lng, place, updatedAt } or null.
 * Remember to allow the tile/map host in the CSP meta tag in index.html.
 */
export function mountMap(el, child) {
  const L = child.location;
  el.textContent = L && L.lat != null
    ? `Map goes here · ${Number(L.lat).toFixed(4)}, ${Number(L.lng).toFixed(4)}`
    : 'Map appears once this child\u2019s device reports its location.';
}
