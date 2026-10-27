// Local test of the backend with Node's built-in SQLite (same engine as Cloudflare D1/DO).
// Run: node --experimental-sqlite backend/test/test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Hub, dayOf } from "../src/index.js";
import { hashPassword, validateGoogleClaims, googleUpsertLogin, applyInvite, ApiError } from "../src/accounts.js";

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

// ---- manual card-to-card payment lifecycle ----
r = await call(hub, "POST", "/admin/settings", { cardEnabled: true, cardNumber: "6037991111112222", cardHolder: "ایلیا موسی" }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "POST", "/admin/plan", { title: "یک‌ماهه پولی", days: 30, pointCost: 0, amountToman: 150000, enabled: true }, adminToken);
assert.equal(r.status, 200); const payPlan = r.data.id;
r = await call(hub, "POST", "/auth/register", { email: "pay@mail.com", username: "payer", password: "secret1", deviceId: "device-pay000001" });
assert.equal(r.status, 200, JSON.stringify(r.data)); const payerTok = r.data.token;

// stage 1: open a 30-minute order; amount + destination card are snapshotted
r = await call(hub, "POST", "/pay/order/create", { login: "payer", password: "secret1", planId: payPlan, method: "card" });
assert.equal(r.status, 200, JSON.stringify(r.data));
const orderId = r.data.orderId, orderToken = r.data.token;
assert.ok(orderId && orderToken, "order id + token returned");
assert.equal(r.data.amount, 150000, "amount snapshotted");
assert.equal(r.data.card.number, "6037991111112222", "destination card returned");
assert.equal(r.data.status, "AWAITING_PAYMENT");
r = await call(hub, "POST", "/pay/order/create", { login: "payer", password: "nope", planId: payPlan, method: "card" });
assert.equal(r.status, 401, "bad credentials rejected at order create");

// status (server-timed countdown)
r = await call(hub, "GET", `/pay/order/status?id=${orderId}&token=${orderToken}`);
assert.equal(r.status, 200); assert.equal(r.data.status, "AWAITING_PAYMENT"); assert.ok(r.data.remainingMs > 0);

// stage 4: submit; six digits of the source card are required for card payments
r = await call(hub, "POST", "/pay/order/submit", { orderId, token: orderToken, trackRef: "TRK123456" });
assert.equal(r.status, 400, "card submit requires last 6 digits");
r = await call(hub, "POST", "/pay/order/submit", { orderId, token: "wrong", last6: "112233", trackRef: "x" });
assert.equal(r.status, 404, "wrong order token rejected");
r = await call(hub, "POST", "/pay/order/submit", { orderId, token: orderToken, trackRef: "TRK123456", last6: "112233", paidApprox: "14:30" });
assert.equal(r.status, 200, JSON.stringify(r.data)); assert.equal(r.data.status, "PENDING_REVIEW");

// admin sees it in review with the captured details
r = await call(hub, "GET", "/admin/crypto-orders?status=pending", undefined, adminToken);
const ord = r.data.orders.find((o) => o.id === orderId);
assert.ok(ord, "order appears in review list");
assert.equal(ord.srcLast6, "112233"); assert.equal(ord.trackRef, "TRK123456"); assert.equal(ord.amount, 150000);

// approve activates pro exactly once
r = await call(hub, "POST", "/admin/crypto-order", { id: orderId, action: "approve" }, adminToken);
assert.equal(r.status, 200, JSON.stringify(r.data)); assert.ok(r.data.proUntil > Date.now()); const firstUntil = r.data.proUntil;
r = await call(hub, "POST", "/admin/crypto-order", { id: orderId, action: "approve" }, adminToken);
assert.equal(r.status, 200); assert.equal(r.data.already, true, "double approve is a no-op");
r = await call(hub, "GET", "/me", undefined, payerTok);
assert.equal(r.data.account.isPro, true, "payer is pro after approval");
assert.equal(r.data.account.proUntil, firstUntil, "pro not extended twice by a repeated approve");

// expiry is enforced on server time, not the UI timer
r = await call(hub, "POST", "/pay/order/create", { login: "payer", password: "secret1", planId: payPlan, method: "card" });
const exId = r.data.orderId, exTok = r.data.token;
hub.sql.exec("UPDATE crypto_orders SET expires = ? WHERE id = ?", Date.now() - 1000, exId);
r = await call(hub, "GET", `/pay/order/status?id=${exId}&token=${exTok}`);
assert.equal(r.data.status, "EXPIRED", "unpaid order expires after the window");
r = await call(hub, "POST", "/pay/order/submit", { orderId: exId, token: exTok, last6: "112233", trackRef: "x" });
assert.equal(r.status, 410, "cannot submit an expired order");

// reject records a reason
r = await call(hub, "POST", "/pay/order/create", { login: "payer", password: "secret1", planId: payPlan, method: "card" });
const rjId = r.data.orderId, rjTok = r.data.token;
r = await call(hub, "POST", "/pay/order/submit", { orderId: rjId, token: rjTok, last6: "445566", trackRef: "TRKREJECT" });
assert.equal(r.status, 200);
r = await call(hub, "POST", "/admin/crypto-order", { id: rjId, action: "reject", reason: "رسید نامعتبر است" }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "GET", "/admin/crypto-orders?status=rejected", undefined, adminToken);
const rord = r.data.orders.find((o) => o.id === rjId);
assert.ok(rord && rord.rejectReason === "رسید نامعتبر است", "reject reason stored");

// audit trail recorded both decisions
r = await call(hub, "GET", "/admin/audit", undefined, adminToken);
assert.ok(r.data.logs.some((l) => l.action === "order_approve"), "approve audited");
assert.ok(r.data.logs.some((l) => l.action === "order_reject"), "reject audited");

// legacy one-step purchase endpoint still works
r = await call(hub, "POST", "/pay/crypto", { login: "payer", password: "secret1", planId: payPlan, method: "crypto", txid: "0xabc123" });
assert.equal(r.status, 200, "legacy /pay/crypto still works");

// ---- google sign-in (server half; real token verification needs a live Google token) ----
r = await call(hub, "POST", "/auth/google", { idToken: "x", deviceId: "device-g0000001" });
assert.equal(r.status, 503, "google rejected until a client id is configured");
r = await call(hub, "POST", "/admin/settings", { googleClientId: "test-client.apps.googleusercontent.com" }, adminToken);
assert.equal(r.status, 200);
r = await call(hub, "GET", "/auth/google-config");
assert.equal(r.data.clientId, "test-client.apps.googleusercontent.com", "google client id exposed to the app");
r = await call(hub, "POST", "/auth/google", { idToken: "", deviceId: "device-g0000001" });
assert.equal(r.status, 400, "missing google token rejected before any network call");

// ---- google claim validation (pure, no network) ----
const CID = "test-client.apps.googleusercontent.com";
const goodClaims = { aud: CID, iss: "https://accounts.google.com", exp: Math.floor(Date.now() / 1000) + 3600, email: "gclaim@gmail.com", email_verified: true, sub: "123", name: "G Claim" };
function rejects(claims, note) {
  try { validateGoogleClaims(claims, CID); assert.fail("should have thrown: " + note); }
  catch (e) { assert.ok(e instanceof ApiError, note + " -> ApiError"); }
}
assert.deepEqual(validateGoogleClaims(goodClaims, CID).email, "gclaim@gmail.com");
rejects({ ...goodClaims, aud: "someone-else.apps.googleusercontent.com" }, "wrong audience");
rejects({ ...goodClaims, iss: "evil.example.com" }, "wrong issuer");
rejects({ ...goodClaims, exp: Math.floor(Date.now() / 1000) - 10 }, "expired");
rejects({ ...goodClaims, email_verified: false }, "unverified email");
try { validateGoogleClaims(goodClaims, ""); assert.fail("no client id should 503"); }
catch (e) { assert.equal(e.status, 503, "not configured -> 503"); }

// ---- google account linking / creation / 2-device (pure, no network) ----
// new account is created from a Google profile
let g = await googleUpsertLogin(hub.sql, { email: "gnew@gmail.com", name: "G New" }, { deviceId: "device-gnew00001" });
assert.ok(g.token && g.account.email === "gnew@gmail.com", "google creates a new account");
const gnewId = g.account.id;
// an existing (password) account with the same email is LINKED, not duplicated
r = await call(hub, "POST", "/auth/register", { email: "glink@gmail.com", username: "glinkuser", password: "secret1", deviceId: "device-glink0001" });
assert.equal(r.status, 200); const glinkId = r.data.account.id;
g = await googleUpsertLogin(hub.sql, { email: "GLINK@gmail.com", name: "x" }, { deviceId: "device-glink0002" });
assert.equal(g.account.id, glinkId, "google links to the existing account by email");
assert.equal(g.account.username, "glinkuser", "existing username preserved on link");
// 2-device limit also applies to Google sign-in
g = await googleUpsertLogin(hub.sql, { email: "gnew@gmail.com", name: "G New" }, { deviceId: "device-gnew00002" });
assert.ok(g.token, "second device allowed");
let blocked = false;
try { await googleUpsertLogin(hub.sql, { email: "gnew@gmail.com", name: "G New" }, { deviceId: "device-gnew00003" }); }
catch (e) { blocked = (e instanceof ApiError && e.status === 403); }
assert.ok(blocked, "third device blocked for google sign-in too");

// ---- invite system with device-based anti-abuse ----
r = await call(hub, "POST", "/admin/settings", { invitePointsInviter: 100, invitePointsInvitee: 50 }, adminToken);
assert.equal(r.status, 200);
assert.equal(r.data.invitePointsInviter, 100); assert.equal(r.data.invitePointsInvitee, 50);
// an inviter
r = await call(hub, "POST", "/auth/register", { email: "boss@mail.com", username: "bossx", password: "secret1", deviceId: "device-boss00001" });
assert.equal(r.status, 200); const bossId = r.data.account.id; const bossTok = r.data.token;
assert.equal(r.data.account.points, 0);
// new user invited by boss on a fresh device: BOTH sides get points
r = await call(hub, "POST", "/auth/register", { email: "inv1@mail.com", username: "inv1x", password: "secret1", deviceId: "device-inv100001", ref: bossId });
assert.equal(r.status, 200); assert.equal(r.data.account.points, 50, "invitee is credited");
r = await call(hub, "GET", "/me", undefined, bossTok);
assert.equal(r.data.account.points, 100, "inviter is credited");
// a SECOND account on the SAME device cannot be invited again (anti self-farming)
r = await call(hub, "POST", "/auth/register", { email: "inv2@mail.com", username: "inv2x", password: "secret1", deviceId: "device-inv100001", ref: bossId });
assert.equal(r.status, 200); assert.equal(r.data.account.points, 0, "same device cannot be invited twice");
r = await call(hub, "GET", "/me", undefined, bossTok);
assert.equal(r.data.account.points, 100, "inviter not credited again for the same device");
// an invalid code is ignored; sign-up still succeeds with no points
r = await call(hub, "POST", "/auth/register", { email: "inv3@mail.com", username: "inv3x", password: "secret1", deviceId: "device-inv300001", ref: "zzzznotarealid" });
assert.equal(r.status, 200); assert.equal(r.data.account.points, 0, "invalid code -> no points, signup ok");
// a new Google account can also use an invite code (fresh device)
g = await googleUpsertLogin(hub.sql, { email: "ginv@gmail.com", name: "G Inv" }, { deviceId: "device-ginv00001", ref: bossId });
assert.equal(g.account.points, 50, "google invitee credited");
r = await call(hub, "GET", "/me", undefined, bossTok);
assert.equal(r.data.account.points, 200, "inviter credited for both invites (inv1 + google), 100 each");
// you cannot invite yourself
const selfBefore = (await call(hub, "GET", "/me", undefined, bossTok)).data.account.points;
applyInvite(hub.sql, { id: bossId }, bossId, "device-boss00099");
r = await call(hub, "GET", "/me", undefined, bossTok);
assert.equal(r.data.account.points, selfBefore, "self-invite credits nothing");

assert.equal(dayOf(Date.UTC(2026, 9, 20, 20, 31)), "2026-10-21");
console.log("ALL BACKEND TESTS PASSED");
