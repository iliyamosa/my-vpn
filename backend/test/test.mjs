// Local test of the backend logic with Node's built-in SQLite.
// Run: node --experimental-sqlite backend/test/test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Hub, isAdmin, dayOf } from "../src/index.js";

function makeSql() {
  const db = new DatabaseSync(":memory:");
  return {
    exec(query, ...args) {
      const q = query.trim();
      if (/^(SELECT|WITH)/i.test(q)) {
        const rows = db.prepare(q).all(...args);
        return { toArray: () => rows };
      }
      if (args.length === 0) {
        db.exec(q);
      } else {
        db.prepare(q).run(...args);
      }
      return { toArray: () => [] };
    },
  };
}

function makeState() {
  const sockets = [];
  return {
    sockets,
    storage: { sql: makeSql() },
    getWebSockets: () => sockets,
    acceptWebSocket: (ws) => sockets.push(ws),
    setWebSocketAutoResponse: () => {},
  };
}

async function call(hub, method, path, body) {
  const req = new Request("https://x" + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const res = await hub.fetch(req);
  return { status: res.status, data: await res.json() };
}

const state = makeState();
const hub = new Hub(state, { REPO: "o/r", BRANCH: "main" });

// Seed config so getConfig does not hit the network.
hub.storeConfig({ revision: 4, ads: { items: [] } });

// ---- config + broadcast
const received = [];
state.sockets.push({ send: (t) => received.push(JSON.parse(t)) });
state.sockets.push({ send: (t) => received.push(JSON.parse(t)) });
let r = await call(hub, "PUT", "/admin/config", { revision: 5, hello: "world" });
assert.equal(r.status, 200);
assert.equal(r.data.delivered, 2);
assert.equal(received.length, 2);
assert.equal(received[0].config.hello, "world");
r = await call(hub, "PUT", "/admin/config", { revision: 3 });
assert.equal(r.status, 409, "older revision must be rejected");
r = await call(hub, "GET", "/config");
assert.equal(r.data.revision, 5);

// ---- events
const now = Date.now();
const devA = "aaaaaaaa-1111-2222-3333-444444444444";
const devB = "bbbbbbbb-1111-2222-3333-444444444444";
r = await call(hub, "POST", "/events", {
  d: devA, m: "Samsung SM-A515F", a: "13", v: 2, p: 0,
  e: [
    { t: "open", ts: now },
    { t: "ad_view", id: "ad1", ts: now },
    { t: "ad_done", id: "ad1", ts: now },
    { t: "ad_click", id: "ad1", ts: now },
    { t: "conn_ok", s: "Germany", ts: now },
    { t: "session", s: "Germany", min: 61, ts: now },
    { t: "rate", s: "Germany", up: 1, ts: now },
    { t: "free_timeout", ts: now },
  ],
});
assert.equal(r.status, 200, JSON.stringify(r.data));
r = await call(hub, "POST", "/events", { d: devA, e: [{ t: "open" }] });
assert.equal(r.status, 429, "flood protection");
r = await call(hub, "POST", "/events", {
  d: devB, m: "Xiaomi 2201117TG", a: "12", v: 2, p: 1,
  e: [
    { t: "ad_view", id: "ad1", ts: now },
    { t: "ad_view", id: "ad2", ts: now - 86400000 },
    { t: "conn_fail", s: "Germany", ts: now },
    { t: "conn_ok", s: "Germany", ts: now },
    { t: "conn_ok", s: "France", ts: now },
    { t: "rate", s: "Germany", up: 0, ts: now },
    { t: "rate", s: "France", up: 1, ts: now },
  ],
});
assert.equal(r.status, 200);
r = await call(hub, "POST", "/events", { d: "bad id!", e: [] });
assert.equal(r.status, 400);

// ---- summary
r = await call(hub, "GET", "/admin/summary?days=7");
assert.equal(r.status, 200);
const s = r.data;
assert.equal(s.totalDevices, 2);
assert.equal(s.proDevices, 1);
assert.equal(s.online, 2);
assert.equal(s.today.active, 2);
assert.equal(s.today.connected, 2);
assert.equal(s.daily.length, 7);
assert.equal(s.daily[6].day, dayOf(now));
assert.equal(s.daily[6].connects, 3);
assert.equal(s.daily[6].fails, 1);
assert.equal(s.daily[6].freeTimeouts, 1);
const ad1 = s.ads.find((a) => a.id === "ad1");
assert.deepEqual([ad1.views, ad1.viewsToday, ad1.clicks, ad1.completes], [2, 2, 1, 1]);
const ad2 = s.ads.find((a) => a.id === "ad2");
assert.equal(ad2.views, 1);
assert.equal(ad2.viewsToday, 0);
const de = s.servers.find((x) => x.name === "Germany");
assert.deepEqual([de.ok, de.fail, de.up, de.down, de.minutes], [2, 1, 1, 1, 61]);
assert.equal(de.successRate, 67);
assert.equal(de.satisfaction, 50);
assert.equal(s.satisfaction, 67); // 2 up / 3 ratings
console.log("summary ok:", JSON.stringify({ online: s.online, today: s.today, satisfaction: s.satisfaction, servers: s.servers }));

// ---- users
r = await call(hub, "GET", "/admin/users?tier=pro");
assert.equal(r.data.total, 1);
assert.equal(r.data.users[0].model, "Xiaomi 2201117TG");
assert.equal(r.data.users[0].connects, 2);
r = await call(hub, "GET", "/admin/users?q=samsung");
assert.equal(r.data.total, 1);
assert.equal(r.data.users[0].connects, 1);
r = await call(hub, "GET", "/admin/users");
assert.equal(r.data.total, 2);

// ---- admin auth
const goodFetch = async () => new Response(JSON.stringify({ permissions: { push: true } }), { status: 200 });
const readOnlyFetch = async () => new Response(JSON.stringify({ permissions: { push: false, pull: true } }), { status: 200 });
const mkReq = (t) => new Request("https://x/admin/summary", { headers: { Authorization: "Bearer " + t } });
assert.equal(await isAdmin(mkReq("ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaa"), { REPO: "o/r" }, goodFetch), true);
assert.equal(await isAdmin(mkReq("ghp_bbbbbbbbbbbbbbbbbbbbbbbbbbbb"), { REPO: "o/r" }, readOnlyFetch), false);
assert.equal(await isAdmin(mkReq("short"), { REPO: "o/r" }, goodFetch), false);
assert.equal(await isAdmin(new Request("https://x/"), { REPO: "o/r" }, goodFetch), false);

// ---- tehran day boundary: 20:31 UTC is already the next day in Tehran
assert.equal(dayOf(Date.UTC(2026, 9, 20, 20, 31)), "2026-10-21");
assert.equal(dayOf(Date.UTC(2026, 9, 20, 20, 29)), "2026-10-20");

console.log("ALL BACKEND TESTS PASSED");
