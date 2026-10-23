import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { MONITORED_APPS, SECTIONS, renderDisclosure } from '../js/child-disclosure.js';

const KT = new URL('../../../apps/parent-android/app/src/main/java/dev/stmedrano/harbor/parent/alerts/MonitoredApps.kt', import.meta.url);

test('the app list matches the Android allowlist labels, in order', () => {
  const src = readFileSync(KT, 'utf8');
  const labels = [...src.matchAll(/^\s*"[\w.]+" to "([^"]+)",?$/gm)].map(m => m[1]);
  assert.deepEqual(MONITORED_APPS, [...new Set(labels)]);
});

test('every feature is disclosed, and the message section says no text is sent', () => {
  assert.deepEqual(SECTIONS.map(s => s.title), ['Pairing', 'Screen time', 'App controls', 'Location', 'What Harbor watches', 'Check-ins']);
  assert.match(SECTIONS.find(s => s.title === 'App controls').lines.join(' '), /Removing an app needs their OK/);
  const watch = SECTIONS.find(s => s.title === 'What Harbor watches').lines.join(' ');
  assert.match(watch, /never sent the message text/);
  for (const a of MONITORED_APPS) assert.ok(watch.includes(a), a);
  assert.match(SECTIONS.find(s => s.title === 'Location').lines.join(' '), /not live tracking/);
});

test('renders as plain text with no off switch for the child', () => {
  const html = renderDisclosure();
  assert.doesNotMatch(html, /<button|<input|data-a=/);
  assert.match(html, /Only your parents can turn these off/);
  assert.match(html, /This browser does not measure screen time, share location or check messages/);
});
