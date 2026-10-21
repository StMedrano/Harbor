import { strict as assert } from "node:assert";
import fixture from "../../../apps/parent-android/app/src/test/resources/controls-v1.json" with { type: "json" };
import { type ControlsV1, emptyControls, validateControls } from "../src/v1/controls.ts";

const controls = (): ControlsV1 => structuredClone(fixture) as ControlsV1;
const rejected = (v: unknown) => assert.throws(() => validateControls(v), TypeError);
const tweak = (f: (c: ControlsV1) => void) => {
  const c = controls() as ControlsV1 & Record<string, unknown>;
  f(c);
  return c;
};

Deno.test("shared fixture and the empty policy are valid", () => {
  assert.deepEqual(validateControls(controls()), fixture);
  assert.deepEqual(validateControls(emptyControls()), emptyControls());
});

Deno.test("unknown keys are rejected at every level", () => {
  rejected({ ...controls(), webFilter: true });
  rejected(tweak((c) => Object.assign(c.pause, { reason: "x" })));
  rejected(tweak((c) => Object.assign(c.bedtime!, { zone: "UTC" })));
  rejected(tweak((c) => Object.assign(c.appLimits[0], { note: "x" })));
  rejected({ ...controls(), version: 2 });
});

Deno.test("pause: until only while active, and a real instant", () => {
  assert.ok(validateControls(tweak((c) => (c.pause = { active: true, until: null }))));
  assert.ok(validateControls(tweak((c) => (c.pause = { active: true, until: "2026-10-20T20:00:00Z" }))));
  rejected(tweak((c) => (c.pause = { active: false, until: "2026-10-20T20:00:00Z" })));
  rejected(tweak((c) => (c.pause = { active: true, until: "2026-02-30T20:00:00Z" })));
  rejected(tweak((c) => (c.pause = { active: "yes" as unknown as boolean, until: null })));
});

Deno.test("bedtime: clock format, distinct ends, valid unique days", () => {
  rejected(tweak((c) => (c.bedtime!.start = "24:00")));
  rejected(tweak((c) => (c.bedtime!.start = "9:00")));
  rejected(tweak((c) => (c.bedtime!.end = c.bedtime!.start)));
  rejected(tweak((c) => (c.bedtime!.days = ["mon", "mon"])));
  rejected(tweak((c) => (c.bedtime!.days = ["monday" as "mon"])));
  rejected(tweak((c) => (c.bedtime!.days = [])));
  assert.ok(validateControls(tweak((c) => (c.bedtime = { enabled: false, start: "21:00", end: "07:00", days: [] }))));
  assert.ok(validateControls(tweak((c) => (c.bedtime = null))));
});

Deno.test("app lists: package format, no duplicates, caps", () => {
  rejected(tweak((c) => c.blockedApps.push("not a package")));
  rejected(tweak((c) => c.blockedApps.push("youtube")));
  rejected(tweak((c) => c.blockedApps.push(c.blockedApps[0])));
  rejected(tweak((c) => (c.alwaysAllowed = Array.from({ length: 51 }, (_, i) => `com.example.app${i}`))));
  rejected(tweak((c) => c.appLimits.push({ ...c.appLimits[0] })));
  rejected(tweak((c) => (c.appLimits[0].minutesPerDay = 0)));
  rejected(tweak((c) => (c.appLimits[0].minutesPerDay = 1441)));
  rejected(tweak((c) => (c.appLimits[0].minutesPerDay = 1.5)));
});

Deno.test("one rule per app", () => {
  rejected(tweak((c) => c.alwaysAllowed.push(c.blockedApps[0])));
  rejected(tweak((c) => c.blockedApps.push(c.appLimits[0].packageName)));
  rejected(tweak((c) => c.alwaysAllowed.push(c.appLimits[0].packageName)));
});

Deno.test("daily limit: bounds, extra time for one day, legacy policies", () => {
  assert.ok(validateControls(tweak((c) => (c.dailyLimit = { minutes: 90, extraDate: "2026-10-21", extraMinutes: 15 }))));
  assert.ok(validateControls(tweak((c) => (c.dailyLimit = null))));
  rejected(tweak((c) => (c.dailyLimit = { minutes: 0, extraDate: null, extraMinutes: 0 })));
  rejected(tweak((c) => (c.dailyLimit = { minutes: 1441, extraDate: null, extraMinutes: 0 })));
  rejected(tweak((c) => (c.dailyLimit = { minutes: 60, extraDate: null, extraMinutes: 15 })));
  rejected(tweak((c) => (c.dailyLimit = { minutes: 60, extraDate: "2026-02-30", extraMinutes: 15 })));
  rejected(tweak((c) => (c.dailyLimit = { minutes: 60, extraDate: "2026-10-21", extraMinutes: 721 })));
  rejected(tweak((c) => Object.assign(c.dailyLimit!, { note: "x" })));
  const legacy = controls() as Partial<ControlsV1>;
  delete legacy.dailyLimit;
  assert.equal(validateControls(legacy).dailyLimit, null);
});

Deno.test("device controls read request and reply", async () => {
  const { validateDeviceControlsReply, validateDeviceControlsRequest } = await import("../src/v1/controls.ts");
  const deviceId = "22222222-2222-4222-8222-222222222222";
  assert.ok(validateDeviceControlsRequest({ version: 1, deviceId }));
  assert.throws(() => validateDeviceControlsRequest({ version: 1, deviceId, familyId: deviceId }));
  assert.ok(validateDeviceControlsReply({ controls: null, desiredStateVersion: 0, acknowledgedVersion: 0 }));
  assert.ok(validateDeviceControlsReply({ controls: controls(), desiredStateVersion: 5, acknowledgedVersion: 4 }));
  assert.throws(() => validateDeviceControlsReply({ controls: controls(), desiredStateVersion: 4, acknowledgedVersion: 5 }));
  assert.throws(() => validateDeviceControlsReply({ controls: { ...controls(), extra: 1 }, desiredStateVersion: 1, acknowledgedVersion: 0 }));
});
