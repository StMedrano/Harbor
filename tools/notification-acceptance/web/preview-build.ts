import { requirePreviewBuild } from "./site.ts";
requirePreviewBuild(Deno.env.get("VERCEL_ENV"));
const repository = new URL("../../../", import.meta.url);
const output = new URL("preview-dist/", repository);
// A fresh output prevents any stale/unreviewed files from entering the artifact.
await Deno.mkdir(output);
const result = await new Deno.Command(Deno.execPath(), {
  args: [
    "run",
    "--frozen",
    "--allow-read",
    "--allow-write",
    "--allow-run",
    new URL("./build.ts", import.meta.url).href,
  ],
  stdout: "inherit",
  stderr: "inherit",
}).output();
if (!result.success) Deno.exit(result.code);
await Deno.mkdir(new URL("acceptance/", output));
// The Harbor Family web app (web/harbor-family) is the site's root. Vite reads the development backend's public
// settings (VITE_SUPABASE_URL, VITE_SUPABASE_ANON_KEY) from the build environment; without them the app says it
// is not connected.
const family = new URL("web/harbor-family/", repository);
for (const args of [["ci", "--no-audit", "--no-fund"], ["run", "build"]]) {
  const step = await new Deno.Command("npm", { args, cwd: family, stdout: "inherit", stderr: "inherit" }).output();
  if (!step.success) Deno.exit(step.code);
}
async function copyTree(from: URL, to: URL): Promise<void> {
  for await (const entry of Deno.readDir(from)) {
    if (entry.isDirectory) {
      await Deno.mkdir(new URL(entry.name + "/", to), { recursive: true });
      await copyTree(new URL(entry.name + "/", from), new URL(entry.name + "/", to));
    } else if (entry.isFile) await Deno.copyFile(new URL(entry.name, from), new URL(entry.name, to));
  }
}
await copyTree(new URL("dist/", family), output);
for (const name of ["index.html", "app.js", "service-worker.js"]) {
  await Deno.copyFile(
    new URL("./dist/" + name, import.meta.url),
    new URL("acceptance/" + name, output),
  );
}
