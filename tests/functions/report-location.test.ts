import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { createReportLocationHandler, type ReportLocationDeps } from "../../supabase/functions/report-location/index.ts";

const device = {
  deviceId: "11111111-1111-4111-8111-111111111111",
  familyId: "22222222-2222-4222-8222-222222222222",
  childId: "33333333-3333-4333-8333-333333333333",
  authUserId: "44444444-4444-4444-8444-444444444444",
};

const point = { latitude: 40.7, longitude: -74, accuracyM: 10, batteryPct: 50, recordedAt: "2026-10-08T12:00:00Z" };

function request(body: unknown, method = "POST") {
  return new Request("https://example.test/report-location", {
    method,
    headers: { "content-type": "application/json" },
    body: method === "GET" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
}

function deps(overrides: Partial<ReportLocationDeps> = {}) {
  const calls: unknown[] = [];
  const value: ReportLocationDeps = {
    requireDeviceProof: async () => device,
    recordLocations: async (input) => {
      calls.push(input);
      return { accepted: input.points.length, rejected: 0, latestRecordedAt: "2026-10-08T12:00:00.000Z" };
    },
    ...overrides,
  };
  return { value, calls };
}

Deno.test("report-location requires device proof before touching the body or the database", async () => {
  let wrote = false;
  const { value } = deps({
    requireDeviceProof: async () => { throw new Error("PROOF_REQUIRED"); },
    recordLocations: async () => { wrote = true; return { accepted: 0, rejected: 0, latestRecordedAt: null }; },
  });
  await assertRejects(() => createReportLocationHandler(value)(request({ points: [point] })), Error, "PROOF_REQUIRED");
  assertEquals(wrote, false);
});

Deno.test("report-location stores the batch for the proven device only", async () => {
  const { value, calls } = deps();
  const response = await createReportLocationHandler(value)(request({ deviceId: "someone-else", points: [point, point] }));
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { accepted: 2, rejected: 0, latestRecordedAt: "2026-10-08T12:00:00.000Z" });
  assertEquals(calls, [{ deviceId: device.deviceId, points: [point, point] }]);
});

Deno.test("report-location rejects malformed bodies without writing", async () => {
  const { value, calls } = deps();
  const handler = createReportLocationHandler(value);
  for (const body of ["{", "null", "[]", { points: [] }, { points: "x" }, { points: Array.from({ length: 51 }, () => point) }, { nope: 1 }]) {
    const response = await handler(request(body));
    assertEquals(response.status, 400);
    assertEquals((await response.json()).code, "VALIDATION_FAILED");
  }
  assertEquals(calls.length, 0);
});

Deno.test("report-location rejects oversized bodies and non-POST", async () => {
  const { value, calls } = deps();
  const handler = createReportLocationHandler(value);
  const big = { points: [{ ...point, pad: "x".repeat(40_000) }] };
  assertEquals((await handler(request(big))).status, 400);
  assertEquals((await handler(request({}, "GET"))).status, 405);
  assertEquals(calls.length, 0);
});

Deno.test("report-location surfaces database failures to the shared error mapper", async () => {
  const { value } = deps({ recordLocations: async () => { throw Object.assign(new Error("DEVICE_REVOKED"), { code: "42501" }); } });
  await assertRejects(() => createReportLocationHandler(value)(request({ points: [point] })), Error, "DEVICE_REVOKED");
});
