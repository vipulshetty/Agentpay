import { defineConfig } from "vite";

/**
 * In development the console talks to the gateway through this proxy, so the browser never needs
 * CORS and the admin token stays in a header the page sends, not in a cookie.
 */
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      "/admin": { target: "http://localhost:8090", changeOrigin: true },
      "/actuator": { target: "http://localhost:8090", changeOrigin: true },
    },
  },
  build: { outDir: "dist", sourcemap: false },
});
