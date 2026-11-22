/**
 * Static host for the console build, plus an optional same-origin proxy for its API calls.
 *
 * The console talks to the gateway through relative paths (/admin, /actuator) so the browser never
 * needs CORS and the admin token stays a header the operator types in. On a host where the gateway
 * lives elsewhere, set GATEWAY_ORIGIN (for example https://agentpaygate-gateway.onrender.com) in the
 * environment; the Worker forwards those two prefixes there and serves the built assets for
 * everything else. Without GATEWAY_ORIGIN only the static site is served.
 */
const PROXY_PREFIXES = ["/admin", "/actuator"];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = env.GATEWAY_ORIGIN;
    const proxied =
      origin &&
      PROXY_PREFIXES.some(
        (prefix) => url.pathname === prefix || url.pathname.startsWith(`${prefix}/`),
      );

    if (proxied) {
      const target = new URL(url.pathname + url.search, origin);
      return fetch(new Request(target, request));
    }

    return env.ASSETS.fetch(request);
  },
};
