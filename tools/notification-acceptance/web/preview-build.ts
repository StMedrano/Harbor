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
await Deno.mkdir(new URL("frontend-bundle/", output));
await Deno.copyFile(
  new URL("index.html", repository),
  new URL("index.html", output),
);
for (let i = 0; i < 6; i++) {
  const name = "frontend-bundle/part-" + i.toString().padStart(2, "0") + ".txt";
  await Deno.copyFile(new URL(name, repository), new URL(name, output));
}
for (const name of ["index.html", "app.js", "service-worker.js"]) {
  await Deno.copyFile(
    new URL("./dist/" + name, import.meta.url),
    new URL("acceptance/" + name, output),
  );
}
