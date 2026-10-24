import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

// The strict CSP in index.html is kept for production builds. In `vite dev` it is relaxed so hot reload (a websocket +
// injected client) works. Harbor's functions are reached through the backend's own gateway, which allows this app's
// origins including a local one, so there is nothing to forward here.
export default defineConfig(() => {
  return {
    plugins: [{
      name: 'dev-relaxed-csp',
      transformIndexHtml(html, ctx) {
        return ctx.server ? html.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>\n?/, '') : html;
      },
    }],
    // The app imports the shared contracts (packages/contracts) from the repository.
    server: { fs: { allow: [fileURLToPath(new URL('../..', import.meta.url))] } },
    build: { target: 'es2020', sourcemap: false },
  };
});
