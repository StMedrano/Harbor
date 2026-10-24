import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanLabel, fixFrom, FIX_MAX_AGE_MS, LABEL_MAX } from '../js/myplace.js';

const at = ms => ({ coords: { latitude: 40.7128, longitude: -74.006, accuracy: 18.4 }, timestamp: ms });

test('a label is trimmed to what the backend accepts', () => {
  assert.equal(cleanLabel('  Alex  '), 'Alex');
  assert.equal(cleanLabel('Alex\tRivera\n'), 'Alex Rivera');
  // The backend counts code points and refuses anything longer, or a value that is not already trimmed.
  const long = cleanLabel('a'.repeat(LABEL_MAX + 10));
  assert.equal([...long].length, LABEL_MAX);
  assert.equal(long, long.trim());
  assert.equal(cleanLabel('   '), '');
  assert.equal(cleanLabel(null), '');
  assert.equal(cleanLabel('Mum \u0000\u001f'), 'Mum');
});

test('a browser position becomes the report the backend expects', () => {
  const now = Date.UTC(2026, 9, 24, 12, 0, 0);
  const fix = fixFrom(at(now - 1000), now);
  assert.deepEqual(fix, { at: new Date(now - 1000).toISOString(), latitude: 40.7128, longitude: -74.006, accuracyM: 18 });
});

test('a place Harbor would refuse is never sent', () => {
  const now = Date.UTC(2026, 9, 24, 12, 0, 0);
  // Older than the backend's limit: a cached position can be, so it is dropped here instead of refused there.
  assert.equal(fixFrom(at(now - FIX_MAX_AGE_MS - 1000), now), null);
  // A clock far ahead is just as unusable.
  assert.equal(fixFrom(at(now + 120_000), now), null);
  assert.equal(fixFrom(null, now), null);
  assert.equal(fixFrom({ coords: {} }, now), null);
  assert.equal(fixFrom({ coords: { latitude: 91, longitude: 0 }, timestamp: now }, now), null);
  assert.equal(fixFrom({ coords: { latitude: 0, longitude: -181 }, timestamp: now }, now), null);
});

test('coordinates carry at most six decimal places, which is what Harbor accepts', () => {
  const now = Date.UTC(2026, 9, 24, 12, 0, 0);
  // A browser reports full double precision. Harbor's contract runs Number(v.toFixed(6)) === v and refuses the whole
  // report when that fails, which is exactly how live sharing broke on 2026-10-24.
  const fix = fixFrom({ coords: { latitude: 40.71280123456789, longitude: -74.00601987654321, accuracy: 18 }, timestamp: now }, now);
  assert.equal(fix.latitude, 40.712801);
  assert.equal(fix.longitude, -74.00602);
  for (const v of [fix.latitude, fix.longitude]) assert.equal(Number(v.toFixed(6)), v, 'the contract would refuse this');
});

test('accuracy is an integer inside the range the backend allows', () => {
  const now = Date.UTC(2026, 9, 24, 12, 0, 0);
  const none = fixFrom({ coords: { latitude: 1, longitude: 2 }, timestamp: now }, now);
  assert.equal(none.accuracyM, 0);
  const huge = fixFrom({ coords: { latitude: 1, longitude: 2, accuracy: 9e9 }, timestamp: now }, now);
  assert.equal(huge.accuracyM, 100_000);
  assert.ok(Number.isSafeInteger(huge.accuracyM));
});
