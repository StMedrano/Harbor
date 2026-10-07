import { defineConfig } from 'vite';

// The strict CSP in index.html is kept for production builds. In `vite dev` it is relaxed so
// hot reload (a websocket + injected client) works.
export default defineConfig({
  plugins: [{
    name: 'dev-relaxed-csp',
    transformIndexHtml(html, ctx) {
      return ctx.server ? html.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>\n?/, '') : html;
    },
  }],
  build: { target: 'es2020', sourcemap: false },
});
