import test from 'node:test';
import assert from 'node:assert/strict';
import { toFamilyMap, fitZoom, pinsFor, renderFamilyMap, statusOf, pos, STALE_AFTER_MS } from '../js/family-map.js';

const NOW = Date.UTC(2026, 9, 24, 12, 0, 0);
const iso = ms => new Date(ms).toISOString();
const child = (id, name, lat, lng, at = NOW - 60_000) =>
  ({ kind: 'child', person_id: id, user_id: null, name, latitude: lat, longitude: lng, accuracy_m: 18, fix_at: at === null ? null : iso(at) });
const parent = (id, user, name, lat, lng, at = NOW - 60_000) =>
  ({ kind: 'parent', person_id: id, user_id: user, name, latitude: lat, longitude: lng, accuracy_m: 25, fix_at: iso(at) });

test('everyone in the family is on the map, children and sharing parents alike', () => {
  const v = toFamilyMap([
    parent('m1', 'u1', 'Olivia', 40.7128, -74.006),
    child('c1', 'Leo', 40.7138, -74.007),
  ], { userId: 'u1', nowMs: NOW });
  assert.equal(v.people.length, 2);
  assert.deepEqual(v.people.map(p => p.kind), ['child', 'parent'], 'children read first');
  assert.equal(v.people.find(p => p.kind === 'parent').self, true, 'you are marked');
  assert.equal(v.placed.length, 2);
});

test('a child with no place yet is still listed, never dropped', () => {
  const v = toFamilyMap([child('c1', 'Maya', null, null, null)], { nowMs: NOW });
  assert.equal(v.people.length, 1);
  assert.equal(v.people[0].placed, false);
  assert.equal(v.placed.length, 0);
  assert.equal(v.centre, null);
  assert.equal(renderFamilyMap(v), '', 'no map to draw without a single place');
  assert.match(statusOf(v.people[0]), /No place yet/);
});

test('a parent only appears when they are sharing, and never chooses for a child', () => {
  // The view returns no row at all for a parent who is not sharing, so the map simply has no parent in it.
  const v = toFamilyMap([child('c1', 'Leo', 40.7128, -74.006)], { userId: 'u1', nowMs: NOW });
  assert.equal(v.people.filter(p => p.kind === 'parent').length, 0);
  assert.equal(v.people.length, 1);
});

test('an old place is marked last known rather than shown as current', () => {
  const v = toFamilyMap([child('c1', 'Leo', 40.7128, -74.006, NOW - STALE_AFTER_MS - 1000)], { nowMs: NOW });
  assert.equal(v.people[0].stale, true);
  assert.match(statusOf(v.people[0], { ago: () => '2 h ago' }), /^Last known/);
  const fresh = toFamilyMap([child('c1', 'Leo', 40.7128, -74.006)], { nowMs: NOW });
  assert.equal(fresh.people[0].stale, false);
  assert.match(statusOf(fresh.people[0], { ago: () => '1 min ago' }), /^Seen/);
});

test('the zoom pulls back until the whole family fits', () => {
  const close = fitZoom([{ lat: 40.7128, lng: -74.006 }, { lat: 40.7138, lng: -74.007 }]);
  const farApart = fitZoom([{ lat: 40.7128, lng: -74.006 }, { lat: 34.0522, lng: -118.2437 }]);
  assert.ok(farApart < close, 'a family across the country zooms out further than one across the street');
  assert.equal(fitZoom([{ lat: 40.7128, lng: -74.006 }]), 15, 'one person keeps the street-level zoom');
  assert.ok(farApart >= 3);
});

test('every pin that is drawn sits inside the window', () => {
  const v = toFamilyMap([
    child('c1', 'Leo', 40.7128, -74.006),
    child('c2', 'Maya', 40.7138, -74.007),
    parent('m1', 'u1', 'Olivia', 40.7118, -74.005),
  ], { userId: 'u1', nowMs: NOW });
  const pins = pinsFor(v);
  assert.equal(pins.length, 3);
  for (const pin of pins.filter(p => p.inside)) {
    assert.ok(Math.abs(pin.dx) <= 400 && Math.abs(pin.dy) <= 140, 'drawn pins stay in the frame');
  }
  assert.ok(pins.some(p => p.inside));
});

test('tiles and pins are placed from the middle of the map, as the single-child map places them', () => {
  const v = toFamilyMap([child('c1', 'Leo', 40.7128, -74.006), parent('m1', 'u1', 'Olivia', 40.7138, -74.005)], { userId: 'u1', nowMs: NOW });
  const html = renderFamilyMap(v);
  // Every tile and every pin is anchored to the centre. An unanchored transform puts them all at the top-left corner,
  // which drew the tiles in one corner and the pins off the map.
  const styles = [...html.matchAll(/class="(?:tile|pin[^"]*)"[^>]*style="([^"]*)"/g)].map(m => m[1]);
  assert.ok(styles.length >= 3, 'tiles and pins are drawn');
  for (const style of styles) {
    assert.match(style, /^left:calc\(50% \+ -?\d+px\);top:calc\(50% \+ -?\d+px\)/);
    assert.ok(!style.includes('transform'), style);
  }
  assert.equal(pos(0, 0), 'left:calc(50% + 0px);top:calc(50% + 0px)');
});

test('a name cannot inject markup into the map', () => {
  const v = toFamilyMap([child('c1', '<img src=x onerror=alert(1)>', 40.7128, -74.006)], { nowMs: NOW });
  const html = renderFamilyMap(v);
  assert.ok(!html.includes('<img src=x'), 'the name is escaped');
  assert.ok(html.includes('&lt;img'));
});
