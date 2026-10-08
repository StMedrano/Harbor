const assets: Record<string, string> = {
  "/": "index.html",
  "/index.html": "index.html",
  "/app.js": "app.js",
  "/service-worker.js": "service-worker.js",
};
export async function assetResponse(
  request: Request,
  read: (name: string) => Promise<Uint8Array>,
): Promise<Response> {
  const url = new URL(request.url);
  if (url.origin !== "http://localhost:3000") {
    return new Response("Forbidden", { status: 403 });
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }
  const name = assets[url.pathname];
  if (!name) return new Response("Not found", { status: 404 });
  try {
    const bytes = await read(name);
    return new Response(
      request.method === "HEAD" ? null : new Uint8Array(bytes),
      {
        headers: {
          "Content-Type": name.endsWith(".html")
            ? "text/html; charset=utf-8"
            : "text/javascript; charset=utf-8",
          "Cache-Control": "no-store",
          "X-Content-Type-Options": "nosniff",
          "Referrer-Policy": "no-referrer",
          "Content-Security-Policy":
            "default-src 'none'; script-src 'self'; connect-src 'self' https://bfvybxkjxilntjgndsrm.supabase.co; worker-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
        },
      },
    );
  } catch (e) {
    if (e instanceof Deno.errors.NotFound) {
      return new Response("Build assets first", { status: 404 });
    }
    throw e;
  }
}
if (import.meta.main) {
  if (Deno.args.length) throw Error("This server only binds localhost:3000.");
  Deno.serve(
    { hostname: "localhost", port: 3000 },
    (request) =>
      assetResponse(
        request,
        (name) => Deno.readFile(new URL(`./dist/${name}`, import.meta.url)),
      ),
  );
}
