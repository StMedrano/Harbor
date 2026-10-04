const developmentRef = "bfvybxkjxilntjgndsrm";

export function assertProductionBackend(ref: string, url: string): void {
  if (!/^[a-z0-9]{20}$/.test(ref) || ref === developmentRef || url !== `https://${ref}.supabase.co`) {
    throw new Error("Production requires an assigned matching Supabase project distinct from development");
  }
}

export function checkProductionMapping(markdown: string, release = false): void {
  const rows = markdown.split(/\r?\n/).filter((line) => /^\|\s*Production\s*\|/i.test(line));
  if (rows.length !== 1) throw new Error("Expected exactly one production environment mapping");
  const fields = rows[0].split("|").slice(1, -1).map((field) => field.trim().replaceAll("`", ""));
  if (fields.length !== 8 || fields[1] !== "production" || rows[0].toLowerCase().includes(developmentRef)) {
    throw new Error("Invalid production mapping or development backend reference");
  }
  if (release || fields[2] !== "UNASSIGNED" || fields[3] !== "UNASSIGNED") assertProductionBackend(fields[2], fields[3]);
}

if (import.meta.main) {
  const release = Deno.args.includes("--production");
  checkProductionMapping(await Deno.readTextFile(new URL("../docs/runbooks/environment-mapping.md", import.meta.url)), release);
  if (release) {
    assertProductionBackend(Deno.env.get("HARBOR_SUPABASE_PROJECT_REF") ?? "", Deno.env.get("NEXT_PUBLIC_SUPABASE_URL") ?? "");
  }
  console.log(release ? "Production backend mapping verified" : "Repository environment mapping verified (production may remain unassigned)");
}
