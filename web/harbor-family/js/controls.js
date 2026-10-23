// App controls for one child phone, the same rules as the Android parent app. Every change is written at once (no Save
// button), one at a time, on top of the latest controls; a change that loses a race with another parent reloads the
// newer controls and applies itself once more, so neither parent's change is lost.
import { validateControls, emptyControls, CONTROLS_MAX_GRANTS } from '../../../packages/contracts/src/v1/controls.ts';

export const GRANT_MS = 15 * 60_000;
export const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const iso = ms => new Date(ms).toISOString();
const live = (g, nowMs) => { const t = Date.parse(g.until); return Number.isFinite(t) && t > nowMs; };

export { emptyControls, validateControls };

/** The rule an app has: no rule of its own, Unlimited, a daily limit, or Blocked. */
export function ruleOf(c, pkg) {
  if (c.blockedApps.includes(pkg)) return { rule: 'blocked' };
  const limit = c.appLimits.find(l => l.packageName === pkg);
  if (limit) return { rule: 'limited', minutes: limit.minutesPerDay };
  if (c.alwaysAllowed.includes(pkg)) return { rule: 'always' };
  return { rule: 'allowed' };
}
/** One rule per app. A new rule is the parent's latest word on that app, so it also ends an earlier yes for it. */
export function withAppRule(c, pkg, rule, minutes = 60) {
  const out = { ...c, blockedApps: c.blockedApps.filter(p => p !== pkg), appLimits: c.appLimits.filter(l => l.packageName !== pkg),
    alwaysAllowed: c.alwaysAllowed.filter(p => p !== pkg), grants: c.grants.filter(g => g.packageName !== pkg) };
  if (rule === 'blocked') out.blockedApps = [...out.blockedApps, pkg];
  if (rule === 'limited') out.appLimits = [...out.appLimits, { packageName: pkg, minutesPerDay: Math.min(1440, Math.max(1, Math.round(minutes))) }];
  if (rule === 'always') out.alwaysAllowed = [...out.alwaysAllowed, pkg];
  return out;
}
/** Pausing the phone is the parent's latest word: it also ends every earlier yes. */
export const withPauseNow = c => ({ ...c, pause: { active: true, until: null }, grants: [] });
export const withResume = c => ({ ...c, pause: { active: false, until: null } });
/** "Give 15 more min": extra time for today only, added to any extra already given today. */
export function withExtraTime(c, today, minutes = 15) {
  if (!c.dailyLimit) return c;
  const already = c.dailyLimit.extraDate === today ? c.dailyLimit.extraMinutes : 0;
  return { ...c, dailyLimit: { ...c.dailyLimit, extraDate: today, extraMinutes: Math.min(720, already + minutes) } };
}
/** The day's total screen time, 5 minutes to 12 hours; null turns it off. Extra time already given today is kept. */
export function withDailyLimit(c, minutes) {
  if (minutes === null) return { ...c, dailyLimit: null };
  const m = Math.min(720, Math.max(5, Math.round(minutes)));
  return { ...c, dailyLimit: c.dailyLimit ? { ...c.dailyLimit, minutes: m } : { minutes: m, extraDate: null, extraMinutes: 0 } };
}
export function withBedtime(c, patch) {
  const bed = c.bedtime || { enabled: false, start: '21:00', end: '07:00', days: [...WEEKDAYS] };
  const next = { ...bed, ...patch };
  if (next.enabled && !next.days.length) next.days = [...WEEKDAYS];
  return { ...c, bedtime: next };
}
/** A parent's yes: [pkg] may be used until [untilMs]. Ended grants and an earlier grant for that app are dropped. */
export function withGrant(c, pkg, untilMs, nowMs) {
  const kept = c.grants.filter(g => g.packageName !== pkg && live(g, nowMs));
  return { ...c, grants: [...kept, { packageName: pkg, until: iso(untilMs) }].slice(-CONTROLS_MAX_GRANTS) };
}
export const withoutGrant = (c, pkg) => ({ ...c, grants: c.grants.filter(g => g.packageName !== pkg) });
export const liveGrants = (c, nowMs) => c.grants.filter(g => live(g, nowMs));

/**
 * One phone's controls as this page knows them. `write(change)` applies `change` to the latest controls and writes the
 * result: on a version conflict it reloads and applies the same change on top of the other parent's version, once.
 */
export class DeviceControls {
  constructor(api, familyId, deviceId, onChange) {
    Object.assign(this, { api, familyId, deviceId, onChange });
    this.view = { status: 'loading', controls: emptyControls(), version: 0, applied: false, saving: false, saveFailed: false };
    this.queue = Promise.resolve(); this.gone = false;
  }
  set(patch) { if (this.gone) return; this.view = { ...this.view, ...patch }; this.onChange(); }
  read(r) { return { controls: r.controls ? validateControls(r.controls) : emptyControls(), version: r.desiredStateVersion, applied: r.acknowledgedVersion >= r.desiredStateVersion }; }
  async load() {
    if (this.view.saving) return; // A change being written shows its own result.
    try { const r = await this.api.getControls(this.deviceId); if (!this.view.saving) this.set({ status: 'ready', ...this.read(r) }); }
    catch (x) { this.set(['forbidden', 'not_found', 'device_revoked'].includes(x && x.code) ? { status: 'lost' } : this.view.status === 'loading' ? { status: 'failed' } : {}); }
  }
  write(change) {
    const run = async () => {
      if (this.view.status !== 'ready') return false;
      const before = this.view;
      let next;
      try { next = validateControls(change(before.controls)); } catch { return false; }
      if (JSON.stringify(next) === JSON.stringify(before.controls)) return true;
      this.set({ controls: next, applied: false, saving: true, saveFailed: false });
      try {
        let version, saved = next;
        try { version = await this.api.writeControls({ familyId: this.familyId, deviceId: this.deviceId, controls: next, expectedVersion: before.version }); }
        catch (x) {
          if (!(x && x.code === 'stale')) throw x;
          const fresh = this.read(await this.api.getControls(this.deviceId));
          saved = validateControls(change(fresh.controls));
          version = await this.api.writeControls({ familyId: this.familyId, deviceId: this.deviceId, controls: saved, expectedVersion: fresh.version });
        }
        this.set({ status: 'ready', controls: saved, version, applied: false, saving: false, saveFailed: false });
        return true;
      } catch (x) {
        this.set(['forbidden', 'not_found', 'device_revoked'].includes(x && x.code) ? { status: 'lost', saving: false }
          : { controls: before.controls, saving: false, saveFailed: true });
        return false;
      }
    };
    const p = this.queue.then(run, run);
    this.queue = p.catch(() => false);
    return p;
  }
  dispose() { this.gone = true; }
}
