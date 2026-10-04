const root = new URL("./", import.meta.url);
const dist = new URL("./dist/", root);
await Deno.mkdir(dist, { recursive: true });
for (const name of ["app", "service-worker"]) {
  const entry = new URL(`./${name}.ts`, root);
  // Task 2 adds the receipt worker; keep lifecycle assets buildable before it exists.
  if (name === "service-worker") {
    try {
      await Deno.stat(entry);
    } catch (e) {
      if (e instanceof Deno.errors.NotFound) continue;
      throw e;
    }
  }
  const result = await new Deno.Command(Deno.execPath(), {
    args: [
      "bundle",
      "--frozen",
      "--platform=browser",
      entry.href,
      "--output",
      new URL(`./${name}.js`, dist).pathname.replace(/^\/([A-Z]:)/, "$1"),
    ],
    stdout: "inherit",
    stderr: "inherit",
  }).output();
  if (!result.success) Deno.exit(result.code);
}
await Deno.copyFile(
  new URL("./index.html", root),
  new URL("./index.html", dist),
);
