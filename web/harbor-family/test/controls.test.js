import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DeviceControls, emptyControls, ruleOf, withAppRule, withPauseNow, withExtraTime, withDailyLimit, withBedtime, withGrant, liveGrants,
} from '../js/controls.js';

const YT = 'com.google.android.youtube', TT = 'com.zhiliaoapp.musically';
const NOW = Date.parse('2026-10-23T18:00:00.000Z');

test('one rule per app, and a new rule ends an earlier yes for that app', () => {
  let c = withGrant(emptyControls(), YT, NOW + 900_000, NOW);
  c = withAppRule(c, YT, 'limited', 30);
  assert.deepEqual(ruleOf(c, YT), { rule: 'limited', minutes: 30 });
  assert.equal(c.grants.length, 0);
  c = withAppRule(c, YT, 'blocked');
  assert.deepEqual(c.blockedApps, [YT]);
  assert.deepEqual(c.appLimits, []);
  assert.deepEqual(ruleOf(withAppRule(c, YT, 'always'), YT), { rule: 'always' });
  assert.deepEqual(ruleOf(withAppRule(c, YT, 'allowed'), YT), { rule: 'allowed' });
});

test('pause ends every yes; extra time is for today only and adds up', () => {
  const c = withGrant(withDailyLimit(emptyControls(), 120), YT, NOW + 900_000, NOW);
  assert.deepEqual(withPauseNow(c).grants, []);
  assert.equal(withPauseNow(c).pause.active, true);
  const once = withExtraTime(c, '2026-10-23'), twice = withExtraTime(once, '2026-10-23');
  assert.deepEqual([twice.dailyLimit.extraDate, twice.dailyLimit.extraMinutes], ['2026-10-23', 30]);
  assert.equal(withExtraTime(twice, '2026-10-24').dailyLimit.extraMinutes, 15);
  assert.equal(withExtraTime(emptyControls(), '2026-10-23').dailyLimit, null);
  assert.equal(withDailyLimit(c, null).dailyLimit, null);
});

test('turning bedtime on never leaves it with no days', () => {
  const c = withBedtime({ ...emptyControls(), bedtime: { enabled: false, start: '21:00', end: '07:00', days: [] } }, { enabled: true });
  assert.equal(c.bedtime.days.length, 7);
});

test('ended grants are dropped when a new yes is given', () => {
  const old = withGrant(emptyControls(), TT, NOW - 1, NOW - 900_000);
  const c = withGrant(old, YT, NOW + 900_000, NOW);
  assert.deepEqual(c.grants.map(g => g.packageName), [YT]);
  assert.equal(liveGrants(c, NOW).length, 1);
  assert.match(c.grants[0].until, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
});

/** A fake server with one device's controls and a version, like update-device-state. */
function server(initial = emptyControls()) {
  const s = { controls: initial, version: 3, acked: 3, writes: 0 };
  s.api = {
    async getControls() { return { controls: s.controls, desiredStateVersion: s.version, acknowledgedVersion: s.acked }; },
    async writeControls({ controls, expectedVersion }) {
      s.writes++;
      if (expectedVersion !== s.version) throw Object.assign(new Error('stale'), { code: 'stale' });
      s.controls = controls; s.version++; return s.version;
    },
  };
  return s;
}

test('two parents changing at once: the later change is applied on top, nothing is lost', async () => {
  const s = server();
  const dc = new DeviceControls(s.api, 'family', 'device', () => {});
  await dc.load();
  // The other parent blocks TikTok after this page loaded.
  s.controls = withAppRule(s.controls, TT, 'blocked'); s.version++;
  assert.equal(await dc.write(c => withAppRule(c, YT, 'limited', 30)), true);
  assert.deepEqual(s.controls.blockedApps, [TT]);
  assert.deepEqual(s.controls.appLimits, [{ packageName: YT, minutesPerDay: 30 }]);
  assert.equal(dc.view.version, s.version);
  assert.equal(dc.view.applied, false);
});

test('changes from this page are written one at a time, each on the version before it', async () => {
  const s = server();
  const dc = new DeviceControls(s.api, 'family', 'device', () => {});
  await dc.load();
  await Promise.all([dc.write(c => withAppRule(c, YT, 'blocked')), dc.write(c => withAppRule(c, TT, 'always'))]);
  assert.equal(s.writes, 2);
  assert.deepEqual([s.controls.blockedApps, s.controls.alwaysAllowed], [[YT], [TT]]);
});

test('a failed write shows the last saved controls again and says so', async () => {
  const s = server();
  const dc = new DeviceControls(s.api, 'family', 'device', () => {});
  await dc.load();
  s.api.writeControls = async () => { throw Object.assign(new Error('offline'), { code: 'network' }); };
  assert.equal(await dc.write(withPauseNow), false);
  assert.equal(dc.view.controls.pause.active, false);
  assert.equal(dc.view.saveFailed, true);
});
