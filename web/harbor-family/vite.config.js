import { defineConfig, loadEnv } from 'vite';
import { fileURLToPath } from 'node:url';

// The strict CSP in index.html is kept for production builds. In `vite dev` it is relaxed so hot reload (a websocket +
// injected client) works. In dev, /api/fn/<name> forwards to the project's functions the way the deployed site's
// forwarder does (api/fn/[name].js at the repository root), without the browser's Origin header.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    plugins: [{
      name: 'dev-relaxed-csp',
      transformIndexHtml(html, ctx) {
        return ctx.server ? html.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>\n?/, '') : html;
      },
    }],
    server: {
      // The app imports the shared contracts (packages/contracts) from the repository.
      fs: { allow: [fileURLToPath(new URL('../..', import.meta.url))] },
      proxy: env.VITE_SUPABASE_URL ? {
        '/api/fn': {
          target: env.VITE_SUPABASE_URL, changeOrigin: true,
          rewrite: p => p.replace(/^\/api\/fn\//, '/functions/v1/'),
          configure: proxy => proxy.on('proxyReq', req => { req.removeHeader('origin'); req.removeHeader('referer'); }),
        },
      } : undefined,
    },
    build: { target: 'es2020', sourcemap: false },
  };
});
