// DIAGNOSTIC build — no imports, no sockets, nothing that can fail at startup.
// If this still returns Cloudflare error 1101, the problem is the deploy/account/config,
// not the Worker code. If it returns the "ok diag3" text, the sockets module was the cause.
export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const uuid = env && env.UUID ? "set" : "none";
      return new Response("ok diag3 path=" + url.pathname + " uuid=" + uuid, {
        status: 200,
        headers: { "Content-Type": "text/plain; charset=utf-8" },
      });
    } catch (err) {
      return new Response("err: " + (err && err.message ? err.message : err), { status: 500 });
    }
  },
};
