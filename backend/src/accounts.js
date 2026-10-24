// FAIROSVPN accounts: registration, login, 2-device limit, points, missions, pro plans, notices.
//
// Privacy by design:
//  - passwords are stored only as PBKDF2 hashes, never in the clear;
//  - no IP address, no visited sites, no traffic and no device contents are stored;
//  - the admin sees account and billing data needed to run the service, nothing from a user's device.

const PBKDF2_ITER = 100000; // Cloudflare Workers' WebCrypto rejects PBKDF2 iteration counts above 100000.
const SESSION_DAYS = 60;
const ADMIN_SESSION_DAYS = 7;
const MAX_DEVICES = 2;
const AVATAR_MAX_CHARS = 300000; // ~220 KB data URL

export class ApiError extends Error {
  constructor(status, message, data) {
    super(message);
    this.status = status;
    this.data = data || null;
  }
}

// ---------------------------------------------------------------- crypto

const enc = new TextEncoder();
function b64(buf) {
  let s = "";
  const b = new Uint8Array(buf);
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
  return btoa(s);
}
function unb64(str) {
  const bin = atob(str);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function b64url(buf) {
  return b64(buf).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export async function sha256hex(text) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(text));
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
export async function hashPassword(password, saltBytes) {
  const salt = saltBytes || crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: PBKDF2_ITER, hash: "SHA-256" }, key, 256);
  return `pbkdf2$${PBKDF2_ITER}$${b64(salt)}$${b64(bits)}`;
}
export async function verifyPassword(password, stored) {
  try {
    const [, iterS, saltS, hashS] = String(stored).split("$");
    const iter = Number(iterS);
    const salt = unb64(saltS);
    const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: iter, hash: "SHA-256" }, key, 256);
    const a = b64(bits);
    // constant-time-ish compare
    if (a.length !== hashS.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ hashS.charCodeAt(i);
    return diff === 0;
  } catch (e) {
    return false;
  }
}
function randomToken() {
  return b64url(crypto.getRandomValues(new Uint8Array(32)));
}
function newId() {
  return [...crypto.getRandomValues(new Uint8Array(6))].map((x) => x.toString(16).padStart(2, "0")).join("");
}

// ---------------------------------------------------------------- schema

export function initAccountTables(sql) {
  sql.exec(`CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    username TEXT UNIQUE NOT NULL,
    display_name TEXT,
    pass_hash TEXT NOT NULL,
    points INTEGER DEFAULT 0,
    pro_until INTEGER DEFAULT 0,
    invited_by TEXT DEFAULT '',
    created INTEGER, updated INTEGER)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS account_devices (
    account_id TEXT NOT NULL, device_id TEXT NOT NULL,
    label TEXT, created INTEGER, last_seen INTEGER,
    PRIMARY KEY (account_id, device_id))`);
  sql.exec(`CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    account_id TEXT, device_id TEXT, is_admin INTEGER DEFAULT 0,
    created INTEGER, expires INTEGER)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS avatars (account_id TEXT PRIMARY KEY, data TEXT, updated INTEGER)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS notices (
    id TEXT PRIMARY KEY, title TEXT, body TEXT, link TEXT, button TEXT,
    enabled INTEGER DEFAULT 1, sort INTEGER DEFAULT 0, created INTEGER)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS plans (
    id TEXT PRIMARY KEY, title TEXT, days INTEGER, price TEXT, point_cost INTEGER DEFAULT 0,
    enabled INTEGER DEFAULT 1, sort INTEGER DEFAULT 0)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS missions (
    id TEXT PRIMARY KEY, title TEXT, body TEXT, kind TEXT, goal INTEGER DEFAULT 1,
    link TEXT, points INTEGER DEFAULT 0, enabled INTEGER DEFAULT 1, sort INTEGER DEFAULT 0)`);
  sql.exec(`CREATE TABLE IF NOT EXISTS mission_done (
    account_id TEXT, mission_id TEXT, done_at INTEGER, PRIMARY KEY (account_id, mission_id))`);
  sql.exec(`CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)`);
  // Pro servers: the real connection links are secret and go only to paying users.
  sql.exec(`CREATE TABLE IF NOT EXISTS pro_servers (
    id TEXT PRIMARY KEY, name TEXT, link TEXT, enabled INTEGER DEFAULT 1, sort INTEGER DEFAULT 0, created INTEGER)`);
  // Payments (one row per purchase attempt through the gateway).
  sql.exec(`CREATE TABLE IF NOT EXISTS payments (
    authority TEXT PRIMARY KEY, account_id TEXT, plan_id TEXT, amount INTEGER,
    status TEXT DEFAULT 'pending', ref_id TEXT, created INTEGER, paid_at INTEGER)`);
  // Crypto payments: the user submits a transaction id, the admin approves it (no bank gateway).
  sql.exec(`CREATE TABLE IF NOT EXISTS crypto_orders (
    id TEXT PRIMARY KEY, account_id TEXT, plan_id TEXT, asset TEXT, txid TEXT,
    status TEXT DEFAULT 'pending', created INTEGER, decided_at INTEGER)`);
  // Older plans tables may lack the numeric amount column; add it once.
  addColumn(sql, "plans", "amount_toman", "INTEGER DEFAULT 0");
}

/** Adds a column if it isn't already there (SQLite has no IF NOT EXISTS for columns). */
function addColumn(sql, table, col, def) {
  try {
    const cols = sql.exec(`PRAGMA table_info(${table})`).toArray().map((r) => r.name);
    if (!cols.includes(col)) sql.exec(`ALTER TABLE ${table} ADD COLUMN ${col} ${def}`);
  } catch (e) {
    // ignore
  }
}

function one(sql, q, ...args) {
  return sql.exec(q, ...args).toArray()[0] || null;
}
function all(sql, q, ...args) {
  return sql.exec(q, ...args).toArray();
}

export function kvGet(sql, key, def = "") {
  const r = one(sql, "SELECT v FROM kv WHERE k = ?", key);
  return r ? r.v : def;
}
export function kvSet(sql, key, value) {
  sql.exec("INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, String(value));
}

// ---------------------------------------------------------------- validation

function cleanEmail(v) {
  const e = String(v || "").trim().toLowerCase();
  if (!/^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/.test(e)) throw new ApiError(400, "ایمیل معتبر نیست.");
  return e;
}
function cleanUsername(v) {
  const u = String(v || "").trim();
  if (!/^[A-Za-z0-9_]{3,20}$/.test(u)) throw new ApiError(400, "نام کاربری باید ۳ تا ۲۰ حرف انگلیسی، عدد یا _ باشد.");
  return u;
}
function cleanPassword(v) {
  const p = String(v || "");
  if (p.length < 6 || p.length > 128) throw new ApiError(400, "رمز عبور باید حداقل ۶ حرف باشد.");
  return p;
}
function cleanDevice(v) {
  const d = String(v || "").trim();
  if (!/^[A-Za-z0-9-]{8,64}$/.test(d)) throw new ApiError(400, "شناسه‌ی دستگاه نامعتبر است.");
  return d;
}

// ---------------------------------------------------------------- public shapes

export function isPro(acc) {
  return !!acc && Number(acc.pro_until || 0) > Date.now();
}
function publicAccount(sql, acc) {
  return {
    id: acc.id,
    email: acc.email,
    username: acc.username,
    displayName: acc.display_name || acc.username,
    points: Number(acc.points || 0),
    proUntil: Number(acc.pro_until || 0),
    isPro: isPro(acc),
    createdAt: Number(acc.created || 0),
    refCode: acc.id,
    hasAvatar: !!one(sql, "SELECT 1 FROM avatars WHERE account_id = ?", acc.id),
  };
}

// ---------------------------------------------------------------- sessions

async function makeSession(sql, accountId, deviceId, isAdmin, days) {
  const token = randomToken();
  const now = Date.now();
  sql.exec(
    "INSERT INTO sessions (token_hash, account_id, device_id, is_admin, created, expires) VALUES (?, ?, ?, ?, ?, ?)",
    await sha256hex(token), accountId, deviceId || "", isAdmin ? 1 : 0, now, now + days * 86400000,
  );
  if (Math.random() < 0.02) sql.exec("DELETE FROM sessions WHERE expires < ?", now);
  return token;
}
export async function resolveSession(sql, token) {
  if (!token) return null;
  const row = one(sql, "SELECT * FROM sessions WHERE token_hash = ?", await sha256hex(token));
  if (!row || Number(row.expires) < Date.now()) return null;
  return row;
}
export async function requireUser(sql, token) {
  const s = await resolveSession(sql, token);
  if (!s || s.is_admin) throw new ApiError(401, "برای این کار باید وارد شوید.");
  const acc = one(sql, "SELECT * FROM accounts WHERE id = ?", s.account_id);
  if (!acc) throw new ApiError(401, "حساب پیدا نشد.");
  // keep the device fresh
  if (s.device_id) sql.exec("UPDATE account_devices SET last_seen = ? WHERE account_id = ? AND device_id = ?", Date.now(), acc.id, s.device_id);
  return { acc, session: s };
}
export async function isAdminToken(sql, token) {
  const s = await resolveSession(sql, token);
  return !!(s && s.is_admin);
}

// ---------------------------------------------------------------- auth

export async function register(sql, body) {
  const email = cleanEmail(body.email);
  const username = cleanUsername(body.username);
  const password = cleanPassword(body.password);
  const device = cleanDevice(body.deviceId);
  const display = String(body.displayName || username).trim().slice(0, 40) || username;
  if (one(sql, "SELECT 1 FROM accounts WHERE email = ?", email)) throw new ApiError(409, "این ایمیل قبلاً ثبت شده است.");
  if (one(sql, "SELECT 1 FROM accounts WHERE lower(username) = lower(?)", username)) throw new ApiError(409, "این نام کاربری گرفته شده است.");
  const id = newId();
  const now = Date.now();
  const refBy = String(body.ref || "").trim().slice(0, 32);
  const refValid = refBy && refBy !== id && one(sql, "SELECT 1 FROM accounts WHERE id = ?", refBy) ? refBy : "";
  sql.exec(
    "INSERT INTO accounts (id, email, username, display_name, pass_hash, points, pro_until, invited_by, created, updated) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?)",
    id, email, username, display, await hashPassword(password), refValid, now, now,
  );
  sql.exec("INSERT INTO account_devices (account_id, device_id, label, created, last_seen) VALUES (?, ?, ?, ?, ?)",
    id, device, String(body.model || "").slice(0, 60), now, now);
  const token = await makeSession(sql, id, device, false, SESSION_DAYS);
  const acc = one(sql, "SELECT * FROM accounts WHERE id = ?", id);
  return { token, account: publicAccount(sql, acc) };
}

export async function login(sql, body) {
  const idf = String(body.login || body.email || body.username || "").trim();
  const password = String(body.password || "");
  const device = cleanDevice(body.deviceId);
  if (!idf) throw new ApiError(400, "نام کاربری یا ایمیل را وارد کنید.");
  const acc = one(sql, "SELECT * FROM accounts WHERE email = lower(?) OR lower(username) = lower(?)", idf, idf);
  if (!acc || !(await verifyPassword(password, acc.pass_hash))) {
    throw new ApiError(401, "نام کاربری یا رمز عبور درست نیست.");
  }
  const now = Date.now();
  const existing = one(sql, "SELECT 1 FROM account_devices WHERE account_id = ? AND device_id = ?", acc.id, device);
  if (!existing) {
    const count = Number(one(sql, "SELECT COUNT(*) AS n FROM account_devices WHERE account_id = ?", acc.id).n || 0);
    if (count >= MAX_DEVICES) {
      const devices = all(sql, "SELECT device_id, label, last_seen FROM account_devices WHERE account_id = ? ORDER BY last_seen DESC", acc.id)
        .map((d) => ({ id: d.device_id, label: d.label || "دستگاه", lastSeen: Number(d.last_seen || 0) }));
      throw new ApiError(403, "device_limit", { message: "این حساب روی ۲ دستگاه فعال است. برای ورود دستگاه جدید، یکی را حذف کنید.", devices });
    }
    sql.exec("INSERT INTO account_devices (account_id, device_id, label, created, last_seen) VALUES (?, ?, ?, ?, ?)",
      acc.id, device, String(body.model || "").slice(0, 60), now, now);
  } else {
    sql.exec("UPDATE account_devices SET last_seen = ?, label = ? WHERE account_id = ? AND device_id = ?",
      now, String(body.model || "").slice(0, 60), acc.id, device);
  }
  const token = await makeSession(sql, acc.id, device, false, SESSION_DAYS);
  return { token, account: publicAccount(sql, acc) };
}

export async function logout(sql, token) {
  if (token) sql.exec("DELETE FROM sessions WHERE token_hash = ?", await sha256hex(token));
  return { ok: true };
}

export async function adminLogin(sql, env, body) {
  const user = String(body.username || "").trim();
  const pass = String(body.password || "");
  const wantUser = (env.ADMIN_USER || "iliyamosa").trim();
  const hash = env.ADMIN_PASS_HASH || kvGet(sql, "admin_pass_hash", "");
  if (!hash) throw new ApiError(503, "ورود ادمین هنوز تنظیم نشده است.");
  if (user.toLowerCase() !== wantUser.toLowerCase() || !(await verifyPassword(pass, hash))) {
    throw new ApiError(401, "نام کاربری یا رمز ادمین درست نیست.");
  }
  const token = await makeSession(sql, "admin", "", true, ADMIN_SESSION_DAYS);
  return { token, admin: true, username: wantUser };
}

// ---------------------------------------------------------------- profile (me)

export function getMe(sql, acc) {
  const devices = all(sql, "SELECT device_id, label, last_seen FROM account_devices WHERE account_id = ? ORDER BY last_seen DESC", acc.id)
    .map((d) => ({ id: d.device_id, label: d.label || "دستگاه", lastSeen: Number(d.last_seen || 0) }));
  const pro = isPro(acc);
  return {
    account: publicAccount(sql, acc),
    devices,
    referrals: Number(one(sql, "SELECT COUNT(*) AS n FROM accounts WHERE invited_by = ?", acc.id).n || 0),
    missions: missionsFor(sql, acc),
    // Pro locations: everyone sees the names (locked, starred); only a paying account gets the real links.
    proShowcase: proShowcase(sql),
    proServers: pro ? proServerLinks(sql) : [],
  };
}

export async function changePassword(sql, acc, body) {
  if (!(await verifyPassword(String(body.old || ""), acc.pass_hash))) throw new ApiError(401, "رمز فعلی درست نیست.");
  const np = cleanPassword(body.new);
  sql.exec("UPDATE accounts SET pass_hash = ?, updated = ? WHERE id = ?", await hashPassword(np), Date.now(), acc.id);
  // other sessions stay valid; the app can offer "log out other devices" separately
  return { ok: true };
}

export function setDisplayName(sql, acc, body) {
  const name = String(body.name || "").trim().slice(0, 40);
  if (name.length < 1) throw new ApiError(400, "نام نمایشی را وارد کنید.");
  sql.exec("UPDATE accounts SET display_name = ?, updated = ? WHERE id = ?", name, Date.now(), acc.id);
  return { ok: true, displayName: name };
}

export function setAvatar(sql, acc, body) {
  const data = String(body.data || "");
  if (!data.startsWith("data:image/")) throw new ApiError(400, "عکس نامعتبر است.");
  if (data.length > AVATAR_MAX_CHARS) throw new ApiError(413, "عکس خیلی بزرگ است.");
  sql.exec("INSERT INTO avatars (account_id, data, updated) VALUES (?, ?, ?) ON CONFLICT(account_id) DO UPDATE SET data = excluded.data, updated = excluded.updated",
    acc.id, data, Date.now());
  return { ok: true };
}
export function getAvatar(sql, id) {
  const r = one(sql, "SELECT data, updated FROM avatars WHERE account_id = ?", id);
  return r ? r.data : null;
}

export function removeDevice(sql, acc, body) {
  const d = String(body.deviceId || "").trim();
  sql.exec("DELETE FROM account_devices WHERE account_id = ? AND device_id = ?", acc.id, d);
  sql.exec("DELETE FROM sessions WHERE account_id = ? AND device_id = ? AND is_admin = 0", acc.id, d);
  return { ok: true };
}

// ---------------------------------------------------------------- notices

export function listNotices(sql) {
  return all(sql, "SELECT id, title, body, link, button, created FROM notices WHERE enabled = 1 ORDER BY sort DESC, created DESC")
    .map((n) => ({ id: n.id, title: n.title, body: n.body, link: n.link || "", button: n.button || "", created: Number(n.created || 0) }));
}
export function adminListNotices(sql) {
  return all(sql, "SELECT * FROM notices ORDER BY sort DESC, created DESC")
    .map((n) => ({ id: n.id, title: n.title, body: n.body, link: n.link || "", button: n.button || "", enabled: !!n.enabled, sort: Number(n.sort || 0), created: Number(n.created || 0) }));
}
export function adminSaveNotice(sql, body) {
  const id = String(body.id || "").trim() || newId();
  const created = Number(one(sql, "SELECT created FROM notices WHERE id = ?", id)?.created || Date.now());
  sql.exec(
    `INSERT INTO notices (id, title, body, link, button, enabled, sort, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET title=excluded.title, body=excluded.body, link=excluded.link, button=excluded.button, enabled=excluded.enabled, sort=excluded.sort`,
    id, String(body.title || "").slice(0, 120), String(body.body || "").slice(0, 2000), String(body.link || "").slice(0, 400),
    String(body.button || "").slice(0, 40), body.enabled === false ? 0 : 1, Number(body.sort || 0), created,
  );
  return { ok: true, id };
}
export function adminDeleteNotice(sql, id) {
  sql.exec("DELETE FROM notices WHERE id = ?", String(id));
  return { ok: true };
}

// ---------------------------------------------------------------- plans

export function getPlan(sql, id) {
  return one(sql, "SELECT * FROM plans WHERE id = ?", String(id));
}
export function listPlans(sql) {
  return all(sql, "SELECT id, title, days, price, point_cost, amount_toman FROM plans WHERE enabled = 1 ORDER BY sort DESC, days ASC")
    .map((p) => ({ id: p.id, title: p.title, days: Number(p.days || 0), price: p.price || "", pointCost: Number(p.point_cost || 0), amountToman: Number(p.amount_toman || 0) }));
}
export function adminListPlans(sql) {
  return all(sql, "SELECT * FROM plans ORDER BY sort DESC, days ASC")
    .map((p) => ({ id: p.id, title: p.title, days: Number(p.days || 0), price: p.price || "", pointCost: Number(p.point_cost || 0), amountToman: Number(p.amount_toman || 0), enabled: !!p.enabled, sort: Number(p.sort || 0) }));
}
export function adminSavePlan(sql, body) {
  const id = String(body.id || "").trim() || newId();
  sql.exec(
    `INSERT INTO plans (id, title, days, price, point_cost, amount_toman, enabled, sort) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET title=excluded.title, days=excluded.days, price=excluded.price, point_cost=excluded.point_cost, amount_toman=excluded.amount_toman, enabled=excluded.enabled, sort=excluded.sort`,
    id, String(body.title || "").slice(0, 80), Math.max(0, Number(body.days || 0)), String(body.price || "").slice(0, 40),
    Math.max(0, Number(body.pointCost || 0)), Math.max(0, Math.trunc(Number(body.amountToman || 0))), body.enabled === false ? 0 : 1, Number(body.sort || 0),
  );
  return { ok: true, id };
}
export function adminDeletePlan(sql, id) {
  sql.exec("DELETE FROM plans WHERE id = ?", String(id));
  return { ok: true };
}

// ---------------------------------------------------------------- pro servers (secret links)

/** Public: names only, so free users can see the locked pro locations with a star. */
export function proShowcase(sql) {
  return all(sql, "SELECT name FROM pro_servers WHERE enabled = 1 ORDER BY sort DESC, name ASC")
    .map((r) => r.name).filter(Boolean);
}
/** Private: the real links, returned only to a paying (pro) account. */
export function proServerLinks(sql) {
  return all(sql, "SELECT name, link FROM pro_servers WHERE enabled = 1 AND link <> '' ORDER BY sort DESC, name ASC")
    .map((r) => ({ name: r.name, link: r.link }));
}
export function adminListProServers(sql) {
  return all(sql, "SELECT * FROM pro_servers ORDER BY sort DESC, name ASC")
    .map((r) => ({ id: r.id, name: r.name, link: r.link || "", enabled: !!r.enabled, sort: Number(r.sort || 0) }));
}
export function adminSaveProServer(sql, body) {
  const id = String(body.id || "").trim() || newId();
  const created = Number(one(sql, "SELECT created FROM pro_servers WHERE id = ?", id)?.created || Date.now());
  sql.exec(
    `INSERT INTO pro_servers (id, name, link, enabled, sort, created) VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET name=excluded.name, link=excluded.link, enabled=excluded.enabled, sort=excluded.sort`,
    id, String(body.name || "").slice(0, 80), String(body.link || "").trim().slice(0, 4000), body.enabled === false ? 0 : 1, Number(body.sort || 0), created,
  );
  return { ok: true, id };
}
export function adminDeleteProServer(sql, id) {
  sql.exec("DELETE FROM pro_servers WHERE id = ?", String(id));
  return { ok: true };
}

// ---------------------------------------------------------------- payments

export function createPayment(sql, p) {
  sql.exec("INSERT INTO payments (authority, account_id, plan_id, amount, status, created) VALUES (?, ?, ?, ?, 'pending', ?)",
    p.authority, p.accountId, p.planId, Math.trunc(p.amount), Date.now());
}
export function getPayment(sql, authority) {
  return one(sql, "SELECT * FROM payments WHERE authority = ?", String(authority));
}
export function markPaid(sql, authority, refId) {
  sql.exec("UPDATE payments SET status = 'paid', ref_id = ?, paid_at = ? WHERE authority = ?", String(refId), Date.now(), authority);
}
export function markFailed(sql, authority) {
  sql.exec("UPDATE payments SET status = 'failed' WHERE authority = ? AND status = 'pending'", authority);
}

/** Extends an account's pro period by a plan's days. Used by payment verification and by redeem. */
export function grantPlan(sql, accId, plan) {
  const now = Date.now();
  const base = Math.max(now, Number(one(sql, "SELECT pro_until FROM accounts WHERE id = ?", accId)?.pro_until || 0));
  const until = base + Number(plan.days || 0) * 86400000;
  sql.exec("UPDATE accounts SET pro_until = ?, updated = ? WHERE id = ?", until, now, accId);
  return until;
}

export function redeemPlan(sql, acc, body) {
  const plan = one(sql, "SELECT * FROM plans WHERE id = ? AND enabled = 1", String(body.planId || ""));
  if (!plan) throw new ApiError(404, "پلن پیدا نشد.");
  const cost = Number(plan.point_cost || 0);
  if (cost <= 0) throw new ApiError(400, "این پلن با امتیاز قابل تهیه نیست.");
  const points = Number(acc.points || 0);
  if (points < cost) throw new ApiError(400, `امتیاز کافی نیست. ${cost - points} امتیاز دیگر لازم دارید.`);
  sql.exec("UPDATE accounts SET points = points - ? WHERE id = ?", cost, acc.id);
  const until = grantPlan(sql, acc.id, { days: plan.days });
  return { ok: true, proUntil: until, points: points - cost };
}

// ---------------------------------------------------------------- crypto orders (no bank gateway)

/** The buyer submits a transaction id; it waits for the admin to approve it. */
export function createCryptoOrder(sql, acc, body) {
  const plan = one(sql, "SELECT * FROM plans WHERE id = ? AND enabled = 1", String(body.planId || ""));
  if (!plan) throw new ApiError(404, "پلن پیدا نشد.");
  const txid = String(body.txid || "").trim().slice(0, 200);
  if (!txid) throw new ApiError(400, "کد تراکنش (TxID) را وارد کنید.");
  const asset = kvGet(sql, "crypto_asset", "");
  const id = newId();
  sql.exec("INSERT INTO crypto_orders (id, account_id, plan_id, asset, txid, status, created) VALUES (?, ?, ?, ?, ?, 'pending', ?)",
    id, acc.id, plan.id, asset, txid, Date.now());
  return { ok: true, id };
}

export function adminListCryptoOrders(sql, status) {
  const where = status ? "WHERE o.status = ?" : "";
  const args = status ? [status] : [];
  return all(sql, `SELECT o.*, a.username AS username, a.email AS email, p.title AS plan_title, p.days AS plan_days
    FROM crypto_orders o LEFT JOIN accounts a ON a.id = o.account_id LEFT JOIN plans p ON p.id = o.plan_id
    ${where} ORDER BY (o.status = 'pending') DESC, o.created DESC LIMIT 200`, ...args)
    .map((o) => ({
      id: o.id, accountId: o.account_id, username: o.username || "", email: o.email || "",
      planId: o.plan_id, planTitle: o.plan_title || "", planDays: Number(o.plan_days || 0),
      asset: o.asset || "", txid: o.txid || "", status: o.status, created: Number(o.created || 0),
    }));
}

export function adminDecideCryptoOrder(sql, body) {
  const o = one(sql, "SELECT * FROM crypto_orders WHERE id = ?", String(body.id || ""));
  if (!o) throw new ApiError(404, "سفارش پیدا نشد.");
  if (o.status !== "pending") return { ok: true, already: true };
  const action = String(body.action || "");
  if (action === "approve") {
    const plan = one(sql, "SELECT * FROM plans WHERE id = ?", o.plan_id);
    if (!plan) throw new ApiError(404, "پلن این سفارش پیدا نشد.");
    const until = grantPlan(sql, o.account_id, { days: plan.days });
    sql.exec("UPDATE crypto_orders SET status = 'approved', decided_at = ? WHERE id = ?", Date.now(), o.id);
    return { ok: true, proUntil: until };
  }
  if (action === "reject") {
    sql.exec("UPDATE crypto_orders SET status = 'rejected', decided_at = ? WHERE id = ?", Date.now(), o.id);
    return { ok: true };
  }
  throw new ApiError(400, "دستور نامعتبر.");
}

// ---------------------------------------------------------------- missions / points

export function missionsFor(sql, acc) {
  const done = new Set(all(sql, "SELECT mission_id FROM mission_done WHERE account_id = ?", acc.id).map((r) => r.mission_id));
  const referrals = Number(one(sql, "SELECT COUNT(*) AS n FROM accounts WHERE invited_by = ?", acc.id).n || 0);
  const hasAvatar = !!one(sql, "SELECT 1 FROM avatars WHERE account_id = ?", acc.id);
  return all(sql, "SELECT * FROM missions WHERE enabled = 1 ORDER BY sort DESC")
    .map((m) => {
      let progress = 0;
      const goal = Math.max(1, Number(m.goal || 1));
      if (m.kind === "photo") progress = hasAvatar ? 1 : 0;
      else if (m.kind === "invite") progress = referrals;
      else progress = done.has(m.id) ? goal : 0; // visit/custom: completed when claimed
      return {
        id: m.id, title: m.title, body: m.body || "", kind: m.kind, link: m.link || "",
        points: Number(m.points || 0), goal, progress: Math.min(progress, goal),
        claimed: done.has(m.id), ready: progress >= goal && !done.has(m.id),
      };
    });
}

/** Awards a mission's points if its condition is met (auto-verified for photo/invite). */
export function claimMission(sql, acc, body) {
  const m = one(sql, "SELECT * FROM missions WHERE id = ? AND enabled = 1", String(body.missionId || ""));
  if (!m) throw new ApiError(404, "ماموریت پیدا نشد.");
  if (one(sql, "SELECT 1 FROM mission_done WHERE account_id = ? AND mission_id = ?", acc.id, m.id)) {
    throw new ApiError(400, "این ماموریت قبلاً دریافت شده است.");
  }
  const goal = Math.max(1, Number(m.goal || 1));
  let ok = false;
  if (m.kind === "photo") ok = !!one(sql, "SELECT 1 FROM avatars WHERE account_id = ?", acc.id);
  else if (m.kind === "invite") ok = Number(one(sql, "SELECT COUNT(*) AS n FROM accounts WHERE invited_by = ?", acc.id).n || 0) >= goal;
  else ok = body.confirm === true; // visit/custom: user confirms after doing it
  if (!ok) throw new ApiError(400, "هنوز شرط این ماموریت کامل نشده است.");
  sql.exec("INSERT INTO mission_done (account_id, mission_id, done_at) VALUES (?, ?, ?)", acc.id, m.id, Date.now());
  sql.exec("UPDATE accounts SET points = points + ? WHERE id = ?", Number(m.points || 0), acc.id);
  const points = Number(one(sql, "SELECT points FROM accounts WHERE id = ?", acc.id).points || 0);
  return { ok: true, awarded: Number(m.points || 0), points };
}

export function adminListMissions(sql) {
  return all(sql, "SELECT * FROM missions ORDER BY sort DESC")
    .map((m) => ({ id: m.id, title: m.title, body: m.body || "", kind: m.kind, goal: Number(m.goal || 1), link: m.link || "", points: Number(m.points || 0), enabled: !!m.enabled, sort: Number(m.sort || 0) }));
}
export function adminSaveMission(sql, body) {
  const id = String(body.id || "").trim() || newId();
  const kind = ["photo", "invite", "visit"].includes(body.kind) ? body.kind : "visit";
  sql.exec(
    `INSERT INTO missions (id, title, body, kind, goal, link, points, enabled, sort) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET title=excluded.title, body=excluded.body, kind=excluded.kind, goal=excluded.goal, link=excluded.link, points=excluded.points, enabled=excluded.enabled, sort=excluded.sort`,
    id, String(body.title || "").slice(0, 80), String(body.body || "").slice(0, 300), kind,
    Math.max(1, Number(body.goal || 1)), String(body.link || "").slice(0, 400), Math.max(0, Number(body.points || 0)),
    body.enabled === false ? 0 : 1, Number(body.sort || 0),
  );
  return { ok: true, id };
}
export function adminDeleteMission(sql, id) {
  sql.exec("DELETE FROM missions WHERE id = ?", String(id));
  sql.exec("DELETE FROM mission_done WHERE mission_id = ?", String(id));
  return { ok: true };
}

// ---------------------------------------------------------------- admin: accounts

export function adminListAccounts(sql, q, offset, limit) {
  const lim = Math.max(1, Math.min(100, Number(limit) || 50));
  const off = Math.max(0, Number(offset) || 0);
  let where = "", args = [];
  if (q) { where = "WHERE email LIKE ? OR username LIKE ? OR display_name LIKE ?"; const p = `%${q}%`; args = [p, p, p]; }
  const total = Number(one(sql, `SELECT COUNT(*) AS n FROM accounts ${where}`, ...args).n || 0);
  const rows = all(sql, `SELECT * FROM accounts ${where} ORDER BY created DESC LIMIT ? OFFSET ?`, ...args, lim, off);
  const now = Date.now();
  return {
    total, offset: off,
    accounts: rows.map((a) => ({
      id: a.id, email: a.email, username: a.username, displayName: a.display_name || a.username,
      points: Number(a.points || 0), proUntil: Number(a.pro_until || 0), isPro: Number(a.pro_until || 0) > now,
      createdAt: Number(a.created || 0),
      devices: Number(one(sql, "SELECT COUNT(*) AS n FROM account_devices WHERE account_id = ?", a.id).n || 0),
      referrals: Number(one(sql, "SELECT COUNT(*) AS n FROM accounts WHERE invited_by = ?", a.id).n || 0),
      hasAvatar: !!one(sql, "SELECT 1 FROM avatars WHERE account_id = ?", a.id),
    })),
  };
}
export async function adminAccountAction(sql, id, body) {
  const acc = one(sql, "SELECT * FROM accounts WHERE id = ?", String(id));
  if (!acc) throw new ApiError(404, "حساب پیدا نشد.");
  const action = String(body.action || "");
  if (action === "grant_days") {
    const until = grantPlan(sql, acc.id, { days: Math.max(0, Number(body.days || 0)) });
    return { ok: true, proUntil: until };
  }
  if (action === "set_pro_until") {
    sql.exec("UPDATE accounts SET pro_until = ?, updated = ? WHERE id = ?", Math.max(0, Number(body.proUntil || 0)), Date.now(), acc.id);
    return { ok: true };
  }
  if (action === "add_points") {
    sql.exec("UPDATE accounts SET points = MAX(0, points + ?) WHERE id = ?", Number(body.points || 0), acc.id);
    return { ok: true, points: Number(one(sql, "SELECT points FROM accounts WHERE id = ?", acc.id).points || 0) };
  }
  if (action === "reset_password") {
    const np = cleanPassword(body.new);
    sql.exec("UPDATE accounts SET pass_hash = ?, updated = ? WHERE id = ?", await hashPassword(np), Date.now(), acc.id);
    sql.exec("DELETE FROM sessions WHERE account_id = ? AND is_admin = 0", acc.id);
    return { ok: true };
  }
  if (action === "clear_devices") {
    sql.exec("DELETE FROM account_devices WHERE account_id = ?", acc.id);
    sql.exec("DELETE FROM sessions WHERE account_id = ? AND is_admin = 0", acc.id);
    return { ok: true };
  }
  if (action === "delete") {
    sql.exec("DELETE FROM accounts WHERE id = ?", acc.id);
    sql.exec("DELETE FROM account_devices WHERE account_id = ?", acc.id);
    sql.exec("DELETE FROM sessions WHERE account_id = ?", acc.id);
    sql.exec("DELETE FROM avatars WHERE account_id = ?", acc.id);
    sql.exec("DELETE FROM mission_done WHERE account_id = ?", acc.id);
    return { ok: true };
  }
  throw new ApiError(400, "دستور نامعتبر.");
}

/** Finds an account by username+password — used by the purchase website before payment. */
export async function findForPurchase(sql, body) {
  const idf = String(body.login || body.username || "").trim();
  const acc = one(sql, "SELECT * FROM accounts WHERE email = lower(?) OR lower(username) = lower(?)", idf, idf);
  if (!acc || !(await verifyPassword(String(body.password || ""), acc.pass_hash))) {
    throw new ApiError(401, "نام کاربری یا رمز عبور درست نیست.");
  }
  return acc;
}
