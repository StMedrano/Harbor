import test from 'node:test';
import assert from 'node:assert/strict';
import { toUsageView, renderUsageView, formatDuration } from '../js/usage-view.js';

const NOW = Date.parse('2026-10-08T12:20:00.000Z');
const day = (localDate, over = {}) => ({
  localDate, startAt: `${localDate}T00:00:00.000Z`, endAt: `${localDate}T23:59:59.999Z`,
  observedThrough: `${localDate}T12:00:00.000Z`, coverageStart: `${localDate}T00:00:00.000Z`,
  quality: 'observed', totalMs: 3600000, apps: [], ...over,
});
const report = (over = {}) => ({
  version: 1, epochId: '11111111-1111-4111-8111-111111111111', sequence: 3, observedAt: '2026-10-08T12:00:00.000Z',
  zoneId: 'America/Chicago', usagePermission: 'granted', inventoryStatus: 'complete',
  inventory: [{ packageName: 'example.a', label: 'App A' }, { packageName: 'example.b', label: 'App B' }],
  days: [day('2026-10-08', { totalMs: 5400000, apps: [{ packageName: 'example.a', foregroundMs: 4200000 }, { packageName: 'example.b', foregroundMs: 3000000 }] })],
  ...over,
});
const reply = (r, receivedAt = '2026-10-08T12:10:00.000Z') => ({ state: 'available', report: r, receivedAt });

test('formats durations and keeps unknown distinct from zero', () => {
  assert.equal(formatDuration(0), '0m');
  assert.equal(formatDuration(65 * 60000), '1h 05m');
  assert.equal(formatDuration(59999), '0m');
  assert.equal(formatDuration(null), 'Unknown');
  assert.equal(formatDuration(undefined), 'Unknown');
});

test('overlapping app times never replace the measured total', () => {
  const v = toUsageView(reply(report()), NOW);
  assert.equal(v.today.totalMs, 5400000);
  const html = renderUsageView(v);
  assert.match(html, /1h 30m/);
  assert.match(html, /1h 10m/);
  assert.match(html, /50m/);
  assert.match(html, /overlap|multi-window|not added/i);
});

test('a day with an unknown total is drawn as unknown, never as a zero-height bar', () => {
  const r = report({ days: [day('2026-10-07', { totalMs: null, quality: 'unavailable', apps: [] }), day('2026-10-08', { totalMs: 0 })] });
  const html = renderUsageView(toUsageView(reply(r), NOW));
  assert.match(html, /class="cc unk"/);
  assert.match(html, /aria-label="[^"]*Oct 7[^"]*unknown/i);
  assert.match(html, /aria-label="[^"]*Oct 8[^"]*0m/);
});

test('malicious labels and package names are escaped', () => {
  const evil = '<img src=x onerror=alert(1)>"&\'';
  const r = report({ inventory: [{ packageName: 'example.a', label: evil }], days: [day('2026-10-08', { apps: [{ packageName: 'example.a', foregroundMs: 60000 }, { packageName: 'x"><script>y</script>', foregroundMs: 1000 }] })] });
  const html = renderUsageView(toUsageView(reply(r), NOW));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('<script'));
  assert.ok(html.includes('&lt;img'));
  assert.ok(html.includes('&lt;script&gt;'));
});

test('the reporting surface exposes no limit, block or hard-stop controls', () => {
  const html = renderUsageView(toUsageView(reply(report()), NOW));
  assert.ok(!/<button/i.test(html));
  assert.ok(!/data-a=/i.test(html));
  assert.ok(!/hard stop|set limit|blocked|can.t be opened|locks when/i.test(html));
  assert.match(html, /measure|read-only|does not (limit|block)/i);
});

test('packages seen in usage but absent from the launcher inventory have an unknown label', () => {
  const r = report({ days: [day('2026-10-08', { apps: [{ packageName: 'ghost.pkg', foregroundMs: 120000 }] })] });
  const v = toUsageView(reply(r), NOW);
  const ghost = v.apps.find(a => a.packageName === 'ghost.pkg');
  assert.equal(ghost.labelKnown, false);
  assert.match(renderUsageView(v), /ghost\.pkg/);
  assert.match(renderUsageView(v), /name unavailable|label unavailable/i);
});

test('inventory apps with no measured time show unknown or none honestly', () => {
  const r = report({ days: [day('2026-10-08', { totalMs: 60000, apps: [{ packageName: 'example.a', foregroundMs: 60000 }] })] });
  const v = toUsageView(reply(r), NOW);
  const b = v.apps.find(a => a.packageName === 'example.b');
  assert.equal(b.foregroundMs, 0);
  assert.equal(b.measured, false);
});

test('denied or unavailable permission makes no usage claim', () => {
  for (const usagePermission of ['denied', 'unavailable']) {
    const r = report({ usagePermission, days: [], inventory: [], inventoryStatus: 'unavailable' });
    const html = renderUsageView(toUsageView(reply(r), NOW));
    assert.match(html, /usage access/i);
    assert.ok(!/class="cc/.test(html));
    assert.ok(!/\b0m\b/.test(html));
    assert.ok(!/Today/.test(html) || /not (measured|available)/i.test(html));
  }
});

test('no report, expired report and unavailable reporting are distinct states', () => {
  assert.equal(toUsageView({ state: 'none', report: null, receivedAt: null }, NOW).status, 'none');
  assert.equal(toUsageView({ state: 'expired', report: null, receivedAt: null }, NOW).status, 'expired');
  assert.equal(toUsageView(null, NOW).status, 'unavailable');
  const texts = ['none', 'expired'].map(state => renderUsageView(toUsageView({ state, report: null, receivedAt: null }, NOW)));
  assert.notEqual(texts[0], texts[1]);
  assert.match(texts[0], /no report/i);
  assert.match(texts[1], /expired|older than 30 days/i);
  assert.match(renderUsageView(toUsageView(null, NOW)), /not available|unavailable/i);
});

test('stale means the server-confirmed receipt is older than 30 minutes, and timestamps always show', () => {
  const fresh = toUsageView(reply(report(), '2026-10-08T12:00:00.000Z'), NOW);
  const stale = toUsageView(reply(report(), '2026-10-08T11:49:59.000Z'), NOW);
  assert.equal(fresh.stale, false);
  assert.equal(stale.stale, true);
  assert.ok(!/stale/i.test(renderUsageView(fresh)));
  const html = renderUsageView(stale);
  assert.match(html, /stale|out of date/i);
  assert.match(html, /<time datetime="2026-10-08T11:49:59.000Z"/);
  assert.match(html, /<time datetime="2026-10-08T12:00:00.000Z"/);
});

test('partial coverage is labelled with where measurement starts', () => {
  const r = report({ days: [day('2026-10-08', { quality: 'partial', coverageStart: '2026-10-08T09:30:00.000Z' })] });
  const html = renderUsageView(toUsageView(reply(r), NOW));
  assert.match(html, /partial/i);
  assert.match(html, /<time datetime="2026-10-08T09:30:00.000Z"/);
});

test('truncated inventory is disclosed and scope is labelled', () => {
  const html = renderUsageView(toUsageView(reply(report({ inventoryStatus: 'truncated' })), NOW));
  assert.match(html, /truncated|first \d+|not every/i);
  assert.match(html, /launchable|this Android profile/i);
});

test('offline refresh keeps the last confirmed report and says so', () => {
  const v = toUsageView(reply(report()), NOW, { offline: true });
  assert.equal(v.offline, true);
  assert.match(renderUsageView(v), /offline|can.t reach/i);
});

test('report is marked device-observed, not server-attested', () => {
  assert.match(renderUsageView(toUsageView(reply(report()), NOW)), /reported by the child.s phone|device-observed/i);
});

test('malformed replies degrade to unavailable instead of throwing', () => {
  for (const bad of [{}, { state: 'available' }, { state: 'available', report: { days: 'x' }, receivedAt: 'nope' }, { state: 'weird' }, 'x', 5]) {
    const v = toUsageView(bad, NOW);
    assert.equal(v.status, 'unavailable');
    assert.doesNotThrow(() => renderUsageView(v));
  }
});
