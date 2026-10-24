// DIAGNOSTIC build — no imports, no sockets, nothing that can fail at startup.
// Runner probe decides: HTTP 200 "ok diag5" => sockets module was the 1101 cause;
// still 1101 => the deploy/account/config is the problem, not the code.
export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const uuid = env && env.UUID ? "set" : "none";
      return new Response("ok diag5 path=" + url.pathname + " uuid=" + uuid, {
        status: 200,
        headers: { "Content-Type": "text/plain; charset=utf-8" },
      });
    } catch (err) {
      return new Response("err: " + (err && err.message ? err.message : err), { status: 500 });
    }
  },
};
