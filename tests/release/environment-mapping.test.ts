import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import { assertProductionBackend, checkProductionMapping } from "../../scripts/check-environment-mapping.ts";
const dev = "bfvybxkjxilntjgndsrm";
const mapping = (ref: string, url: string) => `| Production | production | ${ref} | ${url} | key identifier | origins | vapid identifier | fcm identifier |`;
Deno.test("release gate denies development backend in production ref or URL", () => {
  assertThrows(() => checkProductionMapping(mapping(dev, "UNASSIGNED")));
  assertThrows(() => checkProductionMapping(mapping("different-project", `https://${dev}.supabase.co`)));
});
Deno.test("PR mapping permits explicitly unassigned production but requires one production record", () => {
  checkProductionMapping(mapping("UNASSIGNED", "UNASSIGNED"));
  assertThrows(() => checkProductionMapping(""));
  assertThrows(() => checkProductionMapping(`${mapping("UNASSIGNED", "UNASSIGNED")}\n${mapping("UNASSIGNED", "UNASSIGNED")}`));
});
Deno.test("production release denies missing, development and inconsistent backend configuration", () => {
  for (const [ref, url] of [["UNASSIGNED", "UNASSIGNED"], [dev, `https://${dev}.supabase.co`], ["abcdefghijklmnopqrst", "https://zyxwvutsrqponmlkjihg.supabase.co"], ["abcdefghijklmnopqrst", "http://abcdefghijklmnopqrst.supabase.co"]]) {
    assertThrows(() => assertProductionBackend(ref, url));
  }
  assertEquals(assertProductionBackend("abcdefghijklmnopqrst", "https://abcdefghijklmnopqrst.supabase.co"), undefined);
});
