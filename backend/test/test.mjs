// Local test of the backend with Node's built-in SQLite (same engine as Cloudflare D1/DO).
// Run: node --experimental-sqlite backend/test/test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Hub, dayOf } from "../src/index.js";
import { hashPassword } from "../src/accounts.js";

function makeSql() {
  const db = new DatabaseSync(":memory:");
  return {
    exec(query, ...args) {
      const q = query.trim();
      if (/^(SELECT|WITH)/i.test(q)) return { toArray: () => db.prepare(q).all(...args) };
      if (args.length === 0) db.exec(q); else db.prepare(q).run(...args);
      return { toArray: () => [] };
    },
  };
}
function makeState() {
  const sockets = [];
  return { sockets, storage: { sql: makeSql() }, getWebSockets: () => sockets,
    acceptWebSocket: (ws) => sockets.push(ws), setWebSocketAutoResponse: () => {} };
}
let ENV;
async function makeHub() {
  ENV = { REPO: "o/r", BRANCH: "main", ADMIN_USER: "iliyamosa", ADMIN_PASS_HASH: await hashPassword("admintest") };
  return new Hub(makeState(), ENV);
}
async function call(hub, method, path, bodyObj, token) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = "Bearer " + token;
  const req = new Request("https://x" + path, { method, headers, body: bodyObj === undefined ? undefined : JSON.stringify(bodyObj) });
  const res = await hub.fetch(req);
  let data = null;
  try { data = await res.clone().json(); } catch (e) { data = null; }
  return { status: res.status, data, res };
}

const hub = await makeHub();
hub.storeConfig({ revision: 4, ads: { items: [] } });

// ---- admin login
let r = await call(hub, "POST", "/auth/admin-login", { username: "iliyamosa", password: "wrong" });
assert.equal(r.status, 401, "wrong admin password rejected");
r = await call(hub, "POST", "/auth/admin-login", { username: "iliyamosa", password: "admintest" });
assert.equal(r.status, 200); const adminToken = r.data.token; assert.ok(adminToken);

// admin routes need the token
r = await call(hub, "GET", "/admin/accounts");
assert.equal(r.status, 401, "admin route without token rejected");
r = await call(hub, "GET", "/admin/accounts", undefined, adminToken);
assert.equal(r.status, 200); assert.equal(r.data.total, 0);

// ---- register
const D1 = "device-aaaa1111", D2 = "device-bbbb2222", D3 = "device-cccc3333";
r = await call(hub, "POST", "/auth/register", { email: "ALI@mail.com", username: "ali_r", displayName: "علی", password: "secret1", deviceId: D1, model: "Samsung A51" });
assert.equal(r.status, 200, JSON.stringify(r.data)); const aliTok1 = r.data.token; const aliId = r.data.account.id;
assert.equal(r.data.account.email, "ali@mail.com");
assert.equal(r.data.account.isPro, false);

r = await call(hub, "POST", "/auth/register", { email: "ali@mail.com", username: "other", password: "secret1", deviceId: D2 });
assert.equal(r.status, 409, "duplicate email rejected");
r = await call(hub, "POST", "/auth/register", { email: "x@mail.com", username: "ALI_R", password: "secret1", deviceId: D2 });
assert.equal(r.status, 409, "duplicate username (case-insensitive) rejected");
r = await call(hub, "POST", "/auth/register", { email: "bad", username: "ok", password: "secret1", deviceId: D2 });
assert.equal(r.status, 400, "bad email rejected");

// ---- login + 2-device limit
r = await call(hub, "POST", "/auth/login", { login: "ali_r", password: "secret1", deviceId: D2, model: "iPhone sim" });
assert.equal(r.status, 200, JSON.stringify(r.data)); const aliTok2 = r.data.token;
r = await call(hub, "POST", "/auth/login", { login: "ali@mail.com", password: "secret1", deviceId: D3 });
assert.equal(r.status, 403, "third device blocked"); assert.equal(r.data.error, "device_limit"); assert.equal(r.data.devices.length, 2);
r = await call(hub, "POST", "/auth/login", { login: "ali_r", password: "nope", deviceId: D2 });
assert.equal(r.status, 401, "wrong password rejected");
// free a slot, then third device works
r = await call(hub, "POST", "/me/device/remove", { deviceId: D1 }, aliTok2);
assert.equal(r.status, 200);
r = await call(hub, "POST", "/auth/login", { login: "ali_r", password: "secret1", deviceId: D3 });
assert.equal(r.status, 200, "third device allowed after removal"); const aliTok3 = r.data.token;
// aliTok1 was on D1 which is removed, but its session still resolves (device gone) — removing device deletes its sessions:
r = await call(hub, "GET", "/me", undefined, aliTok1);
assert.equal(r.status, 401, "session of removed device is invalidated");

// ---- avatar
r = await call(hub, "POST", "/me/avatar", { data: "data:image/png;base64,aaaa" }, aliTok2);
assert.equal(r.status, 200);
r = await call(hub, "GET", "/avatar?id=" + aliId);
assert.equal(r.status, 200); assert.ok((r.res.headers.get("Content-Type") || "").startsWith("image/"));

// ---- referrals B and C invited by ali
r = await call(hub, "POST", "/auth/register", { email: "b@mail.com", username: "bob", password: "secret1", deviceId: "device-dddd4444", ref: aliId });
assert.equal(r.status, 200); const bobId = r.data.account.id; const bobTok = r.data.token;
r = await call(hub, "POST", "/auth/register", { email: "c@mail.com", username: "cat", password: "secret1", deviceId: "device-eeee5555", ref: aliId });
assert.equal(r.status, 200);

// ---- missions (admin creates), user claims
r = await call(hub, "POST", "/admin/mission", { title: "عکس پروفایل", kind: "photo", points: 50, goal: 1 }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "POST", "/admin/mission", { title: "دعوت دوستان", kind: "invite", points: 100, goal: 2 }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "GET", "/me", undefined, aliTok2);
const ms = r.data.missions; assert.equal(ms.length, 2);
const photoM = ms.find((m) => m.kind === "photo"); assert.equal(photoM.ready, true, "photo mission ready (avatar set)");
const inviteM = ms.find((m) => m.kind === "invite"); assert.equal(inviteM.progress, 2); assert.equal(inviteM.ready, true);
r = await call(hub, "POST", "/me/mission/claim", { missionId: photoM.id }, aliTok2);
assert.equal(r.status, 200); assert.equal(r.data.points, 50);
r = await call(hub, "POST", "/me/mission/claim", { missionId: inviteM.id }, aliTok2);
assert.equal(r.status, 200); assert.equal(r.data.points, 150);
r = await call(hub, "POST", "/me/mission/claim", { missionId: photoM.id }, aliTok2);
assert.equal(r.status, 400, "cannot claim twice");

// ---- plans: redeem with points
r = await call(hub, "POST", "/admin/plan", { title: "یک ماهه", days: 30, price: "149000", pointCost: 150 }, adminToken);
assert.equal(r.status, 200); const planId = r.data.id;
r = await call(hub, "GET", "/plans");
assert.equal(r.data.plans.length, 1);
r = await call(hub, "POST", "/me/redeem", { planId }, aliTok2);
assert.equal(r.status, 200, JSON.stringify(r.data)); assert.equal(r.data.points, 0);
r = await call(hub, "GET", "/me", undefined, aliTok2);
assert.equal(r.data.account.isPro, true, "ali is pro after redeem");
r = await call(hub, "POST", "/me/redeem", { planId }, aliTok2);
assert.equal(r.status, 400, "not enough points now");

// ---- notices
r = await call(hub, "POST", "/admin/notice", { title: "خوش آمدید", body: "به فایروس خوش آمدید", enabled: true }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "GET", "/notices");
assert.equal(r.data.notices.length, 1); assert.equal(r.data.notices[0].title, "خوش آمدید");

// ---- password change
r = await call(hub, "POST", "/me/password", { old: "wrong", new: "newsecret" }, aliTok2);
assert.equal(r.status, 401);
r = await call(hub, "POST", "/me/password", { old: "secret1", new: "newsecret" }, aliTok2);
assert.equal(r.status, 200);
r = await call(hub, "POST", "/auth/login", { login: "ali_r", password: "newsecret", deviceId: D2 });
assert.equal(r.status, 200, "login with new password");

// ---- admin account management
r = await call(hub, "GET", "/admin/accounts", undefined, adminToken);
assert.equal(r.data.total, 3);
r = await call(hub, "POST", "/admin/account", { id: bobId, action: "grant_days", days: 10 }, adminToken);
assert.equal(r.status, 200); assert.ok(r.data.proUntil > Date.now());
r = await call(hub, "POST", "/admin/account", { id: bobId, action: "add_points", points: 500 }, adminToken);
assert.equal(r.data.points, 500);
r = await call(hub, "POST", "/admin/account", { id: bobId, action: "reset_password", new: "resetpw" }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "POST", "/auth/login", { login: "bob", password: "resetpw", deviceId: "device-ffff6666" });
assert.equal(r.status, 200, "bob can log in with admin-reset password");

// ---- existing config/events/summary still work (with admin token)
const received = [];
hub.state.sockets.push({ send: (t) => received.push(JSON.parse(t)) });
r = await call(hub, "PUT", "/admin/config", { revision: 5, hello: "world" }, adminToken);
assert.equal(r.status, 200); assert.equal(r.data.delivered, 1);
r = await call(hub, "GET", "/config");
assert.equal(r.data.revision, 5);
r = await call(hub, "POST", "/events", { d: "statdev-11112222", m: "Pixel", a: "14", v: 3, p: 0, e: [{ t: "conn_ok", s: "DE" }] });
assert.equal(r.status, 200);
r = await call(hub, "GET", "/admin/summary?days=7", undefined, adminToken);
assert.equal(r.status, 200); assert.ok(r.data.totalDevices >= 1);

assert.equal(dayOf(Date.UTC(2026, 9, 20, 20, 31)), "2026-10-21");
console.log("ALL BACKEND TESTS PASSED");
