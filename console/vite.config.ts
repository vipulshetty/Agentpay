import { defineConfig, loadEnv } from "vite";

/**
 * In development the console talks to the gateway through this proxy, so the browser never needs
 * CORS and the admin token stays in a header the page sends, not in a cookie.
 *
 * Vite 5 has no command-line flag for the dev-server host allowlist, so a hostname passed through
 * `VITE_ALLOWED_HOST` (for example a preview proxy) is added to it for that process only.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const extraHost = env.VITE_ALLOWED_HOST;

  return {
    server: {
      port: 5173,
      allowedHosts: extraHost ? [extraHost] : [],
      proxy: {
        "/admin": { target: "http://localhost:8090", changeOrigin: true },
        "/actuator": { target: "http://localhost:8090", changeOrigin: true },
      },
    },
    build: { outDir: "dist", sourcemap: false },
  };
});
