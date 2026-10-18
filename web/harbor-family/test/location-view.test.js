import test from 'node:test';
import assert from 'node:assert/strict';
import { toLocationView, renderLocationView, renderMap, project, tileGrid, TILE } from '../js/location-view.js';

const NOW = Date.parse('2026-10-18T12:00:00.000Z');
const fix = (at, over = {}) => ({ at, latitude: 41.8781, longitude: -87.6298, accuracyM: 12, provider: 'fused', ...over });
const reply = (over = {}) => ({ state: 'available', status: 'sharing', observedAt: '2026-10-18T11:55:00.000Z', receivedAt: '2026-10-18T11:56:00.000Z', fixes: [fix('2026-10-18T11:50:00.000Z'), fix('2026-10-18T11:55:00.000Z', { latitude: 41.879 })], ...over });

test('newest fix is the marker and a fresh upload is not stale', () => {
  const v = toLocationView(reply(), NOW);
  assert.equal(v.status, 'available');
  assert.equal(v.latest.at, '2026-10-18T11:55:00.000Z');
  assert.equal(v.stale, false);
  assert.match(renderLocationView(v, { name: 'Ava' }), /Latest place/);
});

test('more than 30 minutes since the last upload reads as last known and the pin goes grey', () => {
  const v = toLocationView(reply({ receivedAt: '2026-10-18T11:20:00.000Z' }), NOW);
  assert.equal(v.stale, true);
  const html = renderLocationView(v, { name: 'Ava', color: '#c0563a' });
  assert.match(html, /Last known place/);
  assert.match(html, /class="pin old"/);
});

test('none, expired and malformed replies never draw a map', () => {
  for (const r of [{ state: 'none' }, { state: 'expired' }, null, { state: 'available' }, 'x']) {
    const html = renderLocationView(toLocationView(r, NOW), { name: 'Ava' });
    assert.ok(!html.includes('class="tile"'));
  }
});

test('out-of-range coordinates are dropped', () => {
  const v = toLocationView(reply({ fixes: [fix('2026-10-18T11:55:00.000Z', { latitude: 200 }), fix('2026-10-18T11:54:00.000Z', { longitude: NaN })] }), NOW);
  assert.equal(v.latest, null);
});

test('sharing problems are stated, not hidden', () => {
  const html = renderLocationView(toLocationView(reply({ status: 'permission_lost' }), NOW), { name: 'Ava' });
  assert.match(html, /permission was turned off/);
});

test('tiles cover the marker and carry OpenStreetMap attribution', () => {
  const { centre, tiles } = tileGrid(41.8781, -87.6298);
  assert.ok(tiles.length > 0);
  assert.ok(tiles.some(t => t.dx <= 0 && t.dx > -TILE && t.dy <= 0 && t.dy > -TILE));
  assert.ok(tiles.every(t => t.url.startsWith('https://tile.openstreetmap.org/15/')));
  const p = project(0, 0); assert.equal(Math.round(p.x), Math.round(p.y));
  assert.ok(centre.x > 0);
  assert.match(renderMap(toLocationView(reply(), NOW)), /OpenStreetMap contributors/);
});

test('names and colours are escaped and the view has no controls besides links', () => {
  const html = renderLocationView(toLocationView(reply(), NOW), { name: '<img src=x>', color: '"><script>', initial: '<' });
  assert.ok(!html.includes('<img src=x>'));
  assert.ok(!html.includes('<script'));
  assert.ok(!/<button/i.test(html));
  assert.ok(!/geofence|check in|ring/i.test(html));
});
