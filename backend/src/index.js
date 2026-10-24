// FAIROSVPN backend: live settings, instant updates, anonymous usage stats and user accounts.
//
// Privacy: no IP addresses, no visited sites, no traffic data are stored.
// A device is only a random id chosen by the app, plus phone model / Android / app version.
// Passwords are stored only as PBKDF2 hashes. The admin never sees a password or device content.

import * as A from "./accounts.js";

const TEHRAN_OFFSET_MIN = 210; // Iran has no daylight saving since 2022
const MAX_EVENTS_PER_BATCH = 300;
const KEEP_DAYS = 180;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
};

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...CORS, ...extra },
  });
}

export function dayOf(ts) {
  return new Date(ts + TEHRAN_OFFSET_MIN * 60000).toISOString().slice(0, 10);
}

function str(v, max) {
  if (v === undefined || v === null) return "";
  return String(v).slice(0, max);
}

// ---------------------------------------------------------------- purchase website

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function faNum(n) {
  return String(n).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
}

const PAGE_CSS = `<style>
*{box-sizing:border-box}
:root{--bg:#05060d;--card:rgba(20,22,38,.72);--line:rgba(140,150,210,.18);--ink:#eef0f8;--mute:#9aa0c0;--b1:#3a7bff;--b2:#8b6cff;--b3:#c04bff}
body{margin:0;min-height:100vh;background:radial-gradient(1200px 700px at 80% -10%,rgba(139,108,255,.25),transparent),radial-gradient(900px 600px at 0% 110%,rgba(58,123,255,.22),transparent),var(--bg);color:var(--ink);font-family:Vazirmatn,Tahoma,system-ui,sans-serif;display:flex;align-items:center;justify-content:center;padding:22px}
.wrap{width:100%;max-width:440px}
.card{background:var(--card);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);border:1px solid var(--line);border-radius:26px;padding:26px 22px;box-shadow:0 30px 80px rgba(0,0,0,.5)}
.logo{font-weight:800;font-size:22px;letter-spacing:.18em;text-align:center;background:linear-gradient(90deg,var(--b1),var(--b2),var(--b3));-webkit-background-clip:text;background-clip:text;color:transparent;margin:0 0 2px}
.logo small{font-weight:400;letter-spacing:.3em;font-size:12px}
h1{font-size:17px;font-weight:600;text-align:center;margin:14px 0 4px}
p{color:var(--mute);font-size:13px;line-height:1.8;text-align:center;margin:4px 0}
label{display:block;font-size:13px;color:var(--mute);margin:14px 2px 6px}
input{width:100%;background:rgba(10,12,22,.6);border:1px solid var(--line);border-radius:14px;color:var(--ink);font:inherit;font-size:15px;padding:13px 14px;outline:none}
input:focus{border-color:var(--b2)}
.plans{display:flex;flex-direction:column;gap:10px;margin:8px 0 4px}
.plan{display:flex;align-items:center;gap:12px;border:1.5px solid var(--line);border-radius:16px;padding:14px;cursor:pointer;transition:border-color .2s,background .2s}
.plan.sel{border-color:var(--b2);background:rgba(139,108,255,.12)}
.plan .r{width:18px;height:18px;border-radius:50%;border:2px solid var(--mute);flex:none}
.plan.sel .r{border-color:var(--b2);background:var(--b2);box-shadow:inset 0 0 0 3px var(--card)}
.plan .nm{flex:1}
.plan .nm b{display:block;font-size:15px;font-weight:600}
.plan .nm small{color:var(--mute);font-size:12px}
.plan .pr{font-weight:700;font-size:15px}
.btn{width:100%;margin-top:18px;border:0;border-radius:16px;padding:15px;font:inherit;font-size:16px;font-weight:700;color:#fff;cursor:pointer;background:linear-gradient(90deg,var(--b1),var(--b2),var(--b3))}
.btn:disabled{opacity:.6}
.err{color:#ff8fa3;font-size:13px;text-align:center;margin-top:12px;min-height:16px}
.badge{width:62px;height:62px;border-radius:50%;border:2px solid;display:flex;align-items:center;justify-content:center;font-size:30px;margin:0 auto 10px}
.ref{font-size:13px;color:var(--ink)}
.hint{margin-top:16px;font-size:12px}
.foot{text-align:center;margin-top:16px;font-size:11px;color:var(--mute)}
</style><link href="https://cdn.jsdelivr.net/npm/vazirmatn@33.0.3/Vazirmatn-font-face.css" rel="stylesheet">`;

function buildBuyPage(origin, plans) {
  const planCards = plans.map((p, i) => `
    <div class="plan${i === 0 ? " sel" : ""}" data-id="${esc(p.id)}" onclick="pick(this)">
      <span class="r"></span>
      <span class="nm"><b>${esc(p.title)}</b><small>${faNum(p.days)} روز</small></span>
      <span class="pr">${esc(p.price || (faNum(p.amountToman.toLocaleString("en-US")) + " تومان"))}</span>
    </div>`).join("");
  const body = plans.length
    ? `<div class="plans">${planCards}</div>
       <label>نام کاربری یا ایمیل</label><input id="login" autocomplete="username" dir="ltr">
       <label>رمز عبور</label><input id="pass" type="password" autocomplete="current-password" dir="ltr">
       <button class="btn" id="go" onclick="pay()">پرداخت و فعال‌سازی</button>
       <div class="err" id="err"></div>`
    : `<p style="margin-top:20px">در حال حاضر پلنی برای خرید آنلاین تنظیم نشده است. لطفاً بعداً دوباره سر بزنید یا با پشتیبانی تماس بگیرید.</p>`;
  return `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>خرید اشتراک — FAIROS VPN</title>${PAGE_CSS}</head><body><div class="wrap"><div class="card">
<p class="logo">FAIROS<small> VPN</small></p>
<h1>خرید اشتراک پرو</h1>
<p>با نام کاربری و رمز حساب خودتان وارد شوید؛ بعد از پرداخت، اشتراک در لحظه در برنامه فعال می‌شود.</p>
${body}
<div class="foot">پرداخت امن از طریق درگاه بانکی</div>
</div></div>
<script>
let sel=document.querySelector(".plan.sel")?.dataset.id||"";
function pick(el){document.querySelectorAll(".plan").forEach(p=>p.classList.remove("sel"));el.classList.add("sel");sel=el.dataset.id;}
async function pay(){
  const err=document.getElementById("err");err.textContent="";
  const login=document.getElementById("login").value.trim();
  const pass=document.getElementById("pass").value;
  if(!login||!pass){err.textContent="نام کاربری و رمز را وارد کنید.";return;}
  if(!sel){err.textContent="یک پلن انتخاب کنید.";return;}
  const btn=document.getElementById("go");btn.disabled=true;btn.textContent="در حال انتقال به درگاه…";
  try{
    const r=await fetch("/pay/start",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({login,password:pass,planId:sel})});
    const d=await r.json();
    if(d.ok&&d.redirect){location.href=d.redirect;return;}
    err.textContent=d.error||"خطا در ایجاد پرداخت.";
  }catch(e){err.textContent="ارتباط با سرور برقرار نشد.";}
  btn.disabled=false;btn.textContent="پرداخت و فعال‌سازی";
}
</script></body></html>`;
}

// ---------------------------------------------------------------- admin auth

const authCache = new Map(); // sha256(token) -> expiry ms

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The admin app sends its GitHub token; anyone who can push to the repo is an admin. */
export async function isAdmin(request, env, fetchImpl = fetch) {
  const header = request.headers.get("Authorization") || "";
  const token = header.replace(/^(Bearer|token)\s+/i, "").trim();
  if (!token || token.length < 20 || token.length > 400) return false;
  const key = await sha256(token);
  const now = Date.now();
  const cached = authCache.get(key);
  if (cached && cached > now) return true;
  try {
    const r = await fetchImpl(`https://api.github.com/repos/${env.REPO}`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "fairosvpn-backend",
      },
    });
    if (!r.ok) return false;
    const data = await r.json();
    const p = data.permissions || {};
    if (p.push || p.admin || p.maintain) {
      if (authCache.size > 100) authCache.clear();
      authCache.set(key, now + 10 * 60 * 1000);
      return true;
    }
  } catch (e) {
    // GitHub unreachable: deny
  }
  return false;
}

// ---------------------------------------------------------------- worker entry

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });
    if (path === "/") return json({ ok: true, service: "fairosvpn", build: "20261024-pbkdf100k" });
    // The purchase website is served straight from the Worker.
    if (path === "/buy" || path === "/pay/callback") {
      const stub0 = env.HUB.get(env.HUB.idFromName("main"));
      return stub0.fetch(request);
    }
    // Everything else is handled by the Durable Object, which also does auth.
    const stub = env.HUB.get(env.HUB.idFromName("main"));
    return stub.fetch(request);
  },
};

export function bearer(request) {
  const h = request.headers.get("Authorization") || "";
  return h.replace(/^(Bearer|token)\s+/i, "").trim();
}

// ---------------------------------------------------------------- durable object

export class Hub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sql = state.storage.sql;
    this.lastBatch = new Map(); // device -> ms, simple flood protection
    this.cachedConfig = null;
    this.init();
    try {
      // Keep-alive pings are answered without waking the object up.
      state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    } catch (e) {
      // not available in tests
    }
  }

  init() {
    this.sql.exec(`CREATE TABLE IF NOT EXISTS config (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      json TEXT NOT NULL,
      revision INTEGER NOT NULL,
      updated INTEGER NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      model TEXT, android TEXT, app_version INTEGER,
      pro INTEGER DEFAULT 0,
      first_seen INTEGER, last_seen INTEGER, last_connect INTEGER DEFAULT 0,
      connects INTEGER DEFAULT 0)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS devices_last_seen ON devices(last_seen)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS daily_users (
      day TEXT NOT NULL, device TEXT NOT NULL, connected INTEGER DEFAULT 0,
      PRIMARY KEY (day, device))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS counters (
      day TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, value INTEGER NOT NULL,
      PRIMARY KEY (day, kind, key))`);
    A.initAccountTables(this.sql);
  }

  async isAdminReq(request) {
    const token = bearer(request);
    if (await A.isAdminToken(this.sql, token)) return true;
    // Fallback: a GitHub push token (kept so the old admin tool keeps working during migration).
    return isAdmin(request, this.env);
  }

  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");
    const sql = this.sql;
    const qp = url.searchParams;
    const body = async () => {
      try { return await request.json(); } catch (e) { return {}; }
    };
    const token = bearer(request);
    const user = async () => A.requireUser(sql, token); // throws ApiError(401) if not logged in
    const admin = async () => { if (!(await this.isAdminReq(request))) throw new A.ApiError(401, "دسترسی ادمین لازم است."); };
    try {
      switch (path) {
        // ---- live settings + stats (existing)
        case "/ws": return this.handleWebSocket(request);
        case "/config": return json(await this.getConfig());
        case "/events":
          if (request.method !== "POST") return json({ error: "method" }, 405);
          return this.handleEvents(await body());
        case "/notices": return json({ notices: A.listNotices(sql) });
        case "/plans": return json({ plans: A.listPlans(sql) });
        case "/avatar": {
          const data = A.getAvatar(sql, qp.get("id") || "");
          if (!data) return json({ error: "not found" }, 404);
          const m = /^data:(image\/[a-z+.-]+);base64,(.*)$/i.exec(data);
          if (!m) return json({ error: "bad" }, 500);
          const bin = Uint8Array.from(atob(m[2]), (c) => c.charCodeAt(0));
          return new Response(bin, { headers: { "Content-Type": m[1], "Cache-Control": "public, max-age=60", ...CORS } });
        }

        // ---- auth
        case "/auth/register": return json(await A.register(sql, await body()));
        case "/auth/login": return json(await A.login(sql, await body()));
        case "/auth/logout": return json(await A.logout(sql, token));
        case "/auth/admin-login": return json(await A.adminLogin(sql, this.env, await body()));

        // ---- profile (logged-in user)
        case "/me": { const { acc } = await user(); return json(A.getMe(sql, acc)); }
        case "/me/password": { const { acc } = await user(); return json(await A.changePassword(sql, acc, await body())); }
        case "/me/display-name": { const { acc } = await user(); return json(A.setDisplayName(sql, acc, await body())); }
        case "/me/avatar": { const { acc } = await user(); return json(A.setAvatar(sql, acc, await body())); }
        case "/me/device/remove": { const { acc } = await user(); return json(A.removeDevice(sql, acc, await body())); }
        case "/me/mission/claim": { const { acc } = await user(); return json(A.claimMission(sql, acc, await body())); }
        case "/me/redeem": { const { acc } = await user(); return json(A.redeemPlan(sql, acc, await body())); }

        // ---- admin: config + stats
        case "/admin/ping": await admin(); return json({ ok: true, online: this.onlineCount() });
        case "/admin/config":
          await admin();
          if (request.method === "PUT" || request.method === "POST") return this.putConfig(await body());
          return json(await this.getConfig());
        case "/admin/summary": await admin(); return json(this.summary(Number(qp.get("days") || 14)));
        case "/admin/users": await admin(); return json(this.users(qp.get("tier") || "all", Number(qp.get("offset") || 0), Number(qp.get("limit") || 50), qp.get("q") || ""));

        // ---- admin: accounts
        case "/admin/accounts": await admin(); return json(A.adminListAccounts(sql, qp.get("q") || "", qp.get("offset"), qp.get("limit")));
        case "/admin/account": await admin(); { const b = await body(); return json(await A.adminAccountAction(sql, b.id, b)); }

        // ---- admin: notices / plans / missions
        case "/admin/notices": await admin(); return json({ notices: A.adminListNotices(sql) });
        case "/admin/notice": await admin(); return json(A.adminSaveNotice(sql, await body()));
        case "/admin/notice/delete": await admin(); return json(A.adminDeleteNotice(sql, (await body()).id));
        case "/admin/plans": await admin(); return json({ plans: A.adminListPlans(sql) });
        case "/admin/plan": await admin(); return json(A.adminSavePlan(sql, await body()));
        case "/admin/plan/delete": await admin(); return json(A.adminDeletePlan(sql, (await body()).id));
        case "/admin/missions": await admin(); return json({ missions: A.adminListMissions(sql) });
        case "/admin/mission": await admin(); return json(A.adminSaveMission(sql, await body()));
        case "/admin/mission/delete": await admin(); return json(A.adminDeleteMission(sql, (await body()).id));
        case "/admin/settings": await admin(); return json(this.adminSettings(await body()));

        // ---- pro servers (names are public; links are secret and go only to paying users)
        case "/pro/showcase": return json({ names: A.proShowcase(sql) });
        case "/admin/pro-servers": await admin(); return json({ servers: A.adminListProServers(sql) });
        case "/admin/pro-server": await admin(); return json(A.adminSaveProServer(sql, await body()));
        case "/admin/pro-server/delete": await admin(); return json(A.adminDeleteProServer(sql, (await body()).id));

        // ---- purchase website
        case "/buy": return this.buyPage(url.origin);
        case "/pay/start": return json(await this.payStart(await body(), url.origin));
        case "/pay/callback": return this.payCallback(url);

        default: return json({ error: "not found" }, 404);
      }
    } catch (e) {
      const status = (e && e.status) || 400;
      const out = { error: String((e && e.message) || e) };
      if (e && e.data) Object.assign(out, e.data);
      return json(out, status);
    }
  }

  /** Admin-editable key/value settings (payment merchant id, admin password change). */
  adminSettings(b) {
    if (b && typeof b === "object") {
      if (typeof b.merchantId === "string") A.kvSet(this.sql, "merchant_id", b.merchantId.trim().slice(0, 120));
      if (typeof b.gateway === "string") A.kvSet(this.sql, "gateway", b.gateway.trim().slice(0, 40));
      if (typeof b.sandbox === "boolean") A.kvSet(this.sql, "sandbox", b.sandbox ? "1" : "0");
      if (typeof b.buyUrl === "string") A.kvSet(this.sql, "buy_url", b.buyUrl.trim().slice(0, 200));
    }
    return {
      merchantId: A.kvGet(this.sql, "merchant_id", ""),
      gateway: A.kvGet(this.sql, "gateway", "zarinpal"),
      sandbox: A.kvGet(this.sql, "sandbox", "0") === "1",
      buyUrl: A.kvGet(this.sql, "buy_url", ""),
    };
  }

  // ---------- purchase website + Zarinpal payment

  zpBase() { return A.kvGet(this.sql, "sandbox", "0") === "1" ? "https://sandbox.zarinpal.com" : "https://payment.zarinpal.com"; }

  /** Verifies the buyer, creates a Zarinpal payment and returns the gateway redirect URL. */
  async payStart(body, origin) {
    const acc = await A.findForPurchase(this.sql, body); // throws 401 on bad credentials
    const plan = A.getPlan(this.sql, body.planId);
    if (!plan || !plan.enabled) throw new A.ApiError(404, "پلن انتخابی پیدا نشد.");
    const amountToman = Math.trunc(Number(plan.amount_toman || 0));
    if (amountToman <= 0) throw new A.ApiError(400, "این پلن برای خرید آنلاین تنظیم نشده است. با پشتیبانی تماس بگیرید.");
    const merchantId = A.kvGet(this.sql, "merchant_id", "");
    if (!merchantId) throw new A.ApiError(503, "درگاه پرداخت هنوز توسط مدیر تنظیم نشده است.");
    const amountRial = amountToman * 10;
    const callback = `${origin}/pay/callback`;
    const r = await fetch(`${this.zpBase()}/pg/v4/payment/request.json`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        merchant_id: merchantId, amount: amountRial, callback_url: callback,
        description: `${plan.title} — FAIROS VPN`, metadata: { email: acc.email },
      }),
    });
    const data = await r.json().catch(() => ({}));
    const code = data?.data?.code;
    if (code !== 100 || !data?.data?.authority) {
      const msg = Array.isArray(data?.errors) && data.errors[0]?.message ? data.errors[0].message : "ایجاد تراکنش ناموفق بود.";
      throw new A.ApiError(502, `خطای درگاه پرداخت: ${msg}`);
    }
    const authority = data.data.authority;
    A.createPayment(this.sql, { authority, accountId: acc.id, planId: plan.id, amount: amountRial });
    return { ok: true, redirect: `${this.zpBase()}/pg/StartPay/${authority}` };
  }

  /** Gateway return URL: verifies the payment and instantly activates pro for the buyer. */
  async payCallback(url) {
    const authority = url.searchParams.get("Authority") || "";
    const status = url.searchParams.get("Status") || "";
    const pay = A.getPayment(this.sql, authority);
    if (!pay) return this.resultPage(false, "تراکنش پیدا نشد.");
    if (pay.status === "paid") return this.resultPage(true, "این پرداخت قبلاً ثبت شده و اشتراک شما فعال است.", pay.ref_id);
    if (status !== "OK") { A.markFailed(this.sql, authority); return this.resultPage(false, "پرداخت توسط شما لغو شد یا ناموفق بود."); }
    const merchantId = A.kvGet(this.sql, "merchant_id", "");
    try {
      const r = await fetch(`${this.zpBase()}/pg/v4/payment/verify.json`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ merchant_id: merchantId, amount: Number(pay.amount), authority }),
      });
      const data = await r.json().catch(() => ({}));
      const code = data?.data?.code;
      if (code === 100 || code === 101) {
        const refId = String(data?.data?.ref_id || "");
        A.markPaid(this.sql, authority, refId);
        const plan = A.getPlan(this.sql, pay.plan_id);
        if (plan) A.grantPlan(this.sql, pay.account_id, { days: plan.days });
        return this.resultPage(true, "پرداخت با موفقیت انجام شد و اشتراک پرو شما همین حالا فعال شد.", refId);
      }
      A.markFailed(this.sql, authority);
      return this.resultPage(false, "تأیید پرداخت ناموفق بود. اگر مبلغی کسر شده تا ۷۲ ساعت برمی‌گردد.");
    } catch (e) {
      return this.resultPage(false, "ارتباط با درگاه برقرار نشد. اگر مبلغی کسر شده با پشتیبانی تماس بگیرید.");
    }
  }

  resultPage(ok, message, refId) {
    const color = ok ? "#2ff5c8" : "#ff6b8a";
    const icon = ok ? "✓" : "✕";
    const ref = refId ? `<p class="ref">کد پیگیری: ${esc(String(refId))}</p>` : "";
    const html = `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FAIROS VPN — نتیجه پرداخت</title>${PAGE_CSS}</head><body><div class="wrap"><div class="card">
<div class="badge" style="color:${color};border-color:${color}">${icon}</div>
<h1>${ok ? "پرداخت موفق" : "پرداخت ناموفق"}</h1><p>${esc(message)}</p>${ref}
<p class="hint">حالا به برنامه‌ی FAIROS VPN برگردید. اشتراک شما تا چند ثانیه‌ی دیگر در برنامه نمایش داده می‌شود.</p>
</div></div></body></html>`;
    return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", ...CORS } });
  }

  buyPage(origin) {
    const plans = A.listPlans(this.sql).filter((p) => Number(p.amountToman || 0) > 0);
    const html = buildBuyPage(origin, plans);
    return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", ...CORS } });
  }

  // ---------- live settings

  async getConfig() {
    if (this.cachedConfig) return this.cachedConfig;
    const row = this.sql.exec("SELECT json FROM config WHERE id = 1").toArray()[0];
    if (row) {
      this.cachedConfig = JSON.parse(row.json);
      return this.cachedConfig;
    }
    // First run: start from the copy in the GitHub repository.
    try {
      const r = await fetch(
        `https://raw.githubusercontent.com/${this.env.REPO}/${this.env.BRANCH || "main"}/remote/config.json?t=${Date.now()}`,
      );
      if (r.ok) {
        const cfg = await r.json();
        this.storeConfig(cfg);
        return cfg;
      }
    } catch (e) {
      // ignore
    }
    return { revision: 0 };
  }

  storeConfig(cfg) {
    const revision = Number(cfg.revision || 0);
    this.sql.exec(
      `INSERT INTO config (id, json, revision, updated) VALUES (1, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET json = excluded.json, revision = excluded.revision, updated = excluded.updated`,
      JSON.stringify(cfg),
      revision,
      Date.now(),
    );
    this.cachedConfig = cfg;
  }

  async putConfig(cfg) {
    if (!cfg || typeof cfg !== "object" || Array.isArray(cfg)) return json({ error: "bad config" }, 400);
    const current = await this.getConfig();
    const rev = Number(cfg.revision || 0);
    if (rev < Number(current.revision || 0)) {
      return json({ error: "older revision", revision: current.revision }, 409);
    }
    this.storeConfig(cfg);
    const sent = this.broadcast(JSON.stringify({ type: "config", config: cfg }));
    return json({ ok: true, revision: rev, delivered: sent });
  }

  // ---------- websockets

  handleWebSocket(request) {
    if (request.headers.get("Upgrade") !== "websocket") return json({ error: "expected websocket" }, 426);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server);
    const cfg = this.cachedConfig;
    if (cfg) {
      server.send(JSON.stringify({ type: "config", config: cfg }));
    } else {
      this.getConfig().then((c) => {
        try {
          server.send(JSON.stringify({ type: "config", config: c }));
        } catch (e) {
          // closed
        }
      });
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  onlineCount() {
    try {
      return this.state.getWebSockets().length;
    } catch (e) {
      return 0;
    }
  }

  broadcast(text) {
    let n = 0;
    for (const ws of this.state.getWebSockets()) {
      try {
        ws.send(text);
        n++;
      } catch (e) {
        // dead socket
      }
    }
    return n;
  }

  async webSocketMessage(ws, message) {
    if (message === "ping") ws.send("pong");
  }

  async webSocketClose(ws, code) {
    try {
      ws.close(code === 1005 ? 1000 : code, "bye");
    } catch (e) {
      // already closed
    }
  }

  async webSocketError(ws) {
    try {
      ws.close(1011, "error");
    } catch (e) {
      // ignore
    }
  }

  // ---------- events

  handleEvents(body) {
    const now = Date.now();
    const device = str(body && body.d, 64);
    if (!/^[A-Za-z0-9-]{8,64}$/.test(device)) return json({ error: "bad device" }, 400);
    const last = this.lastBatch.get(device) || 0;
    if (now - last < 3000) return json({ error: "slow down" }, 429);
    this.lastBatch.set(device, now);
    if (this.lastBatch.size > 50000) this.lastBatch.clear();

    const events = Array.isArray(body.e) ? body.e.slice(0, MAX_EVENTS_PER_BATCH) : [];
    const counters = new Map(); // "day|kind|key" -> n
    const activeDays = new Map(); // day -> connected(0/1)
    let connects = 0;
    let lastConnect = 0;

    const add = (day, kind, key, n = 1) => {
      const k = `${day}|${kind}|${key}`;
      counters.set(k, (counters.get(k) || 0) + n);
    };

    for (const ev of events) {
      if (!ev || typeof ev !== "object") continue;
      let ts = Number(ev.ts || now);
      if (!Number.isFinite(ts) || ts > now + 86400000 || ts < now - 7 * 86400000) ts = now;
      const day = dayOf(ts);
      const t = str(ev.t, 20);
      const server = str(ev.s, 80);
      const id = str(ev.id, 60);
      if (!activeDays.has(day)) activeDays.set(day, 0);
      switch (t) {
        case "open":
          break;
        case "conn_ok":
          activeDays.set(day, 1);
          connects++;
          lastConnect = Math.max(lastConnect, ts);
          add(day, "conn_ok", server);
          break;
        case "conn_fail":
          add(day, "conn_fail", server);
          break;
        case "session": {
          const min = Math.max(0, Math.min(24 * 60, Math.round(Number(ev.min) || 0)));
          if (min > 0) add(day, "minutes", server, min);
          break;
        }
        case "rate":
          add(day, ev.up ? "rate_up" : "rate_down", server);
          break;
        case "ad_view":
        case "ad_click":
        case "ad_done":
          if (id) add(day, t, id);
          break;
        case "free_timeout":
          add(day, "free_timeout", "");
          break;
        default:
          break;
      }
    }

    const today = dayOf(now);
    if (!activeDays.has(today)) activeDays.set(today, 0);

    this.sql.exec(
      `INSERT INTO devices (id, model, android, app_version, pro, first_seen, last_seen, connects, last_connect)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         model = excluded.model, android = excluded.android, app_version = excluded.app_version,
         pro = excluded.pro, last_seen = excluded.last_seen,
         connects = devices.connects + excluded.connects,
         last_connect = MAX(devices.last_connect, excluded.last_connect)`,
      device,
      str(body.m, 60),
      str(body.a, 20),
      Math.trunc(Number(body.v) || 0),
      body.p ? 1 : 0,
      now,
      now,
      connects,
      lastConnect,
    );
    for (const [day, connected] of activeDays) {
      this.sql.exec(
        `INSERT INTO daily_users (day, device, connected) VALUES (?, ?, ?)
         ON CONFLICT(day, device) DO UPDATE SET connected = MAX(daily_users.connected, excluded.connected)`,
        day,
        device,
        connected,
      );
    }
    for (const [k, n] of counters) {
      const [day, kind, key] = k.split("|");
      this.sql.exec(
        `INSERT INTO counters (day, kind, key, value) VALUES (?, ?, ?, ?)
         ON CONFLICT(day, kind, key) DO UPDATE SET value = counters.value + excluded.value`,
        day,
        kind,
        key,
        n,
      );
    }
    if (Math.random() < 0.005) this.cleanup(now);
    return json({ ok: true, accepted: events.length });
  }

  cleanup(now) {
    const oldest = dayOf(now - KEEP_DAYS * 86400000);
    this.sql.exec("DELETE FROM daily_users WHERE day < ?", oldest);
    this.sql.exec("DELETE FROM counters WHERE day < ?", oldest);
  }

  // ---------- admin views

  summary(daysParam) {
    const days = Math.max(1, Math.min(90, Math.trunc(daysParam) || 14));
    const now = Date.now();
    const today = dayOf(now);
    const from = dayOf(now - (days - 1) * 86400000);

    const dayRows = this.sql
      .exec(
        `SELECT day, COUNT(*) AS active, SUM(connected) AS connected
         FROM daily_users WHERE day >= ? GROUP BY day ORDER BY day`,
        from,
      )
      .toArray();
    const connRows = this.sql
      .exec(
        `SELECT day, kind, SUM(value) AS n FROM counters
         WHERE day >= ? AND kind IN ('conn_ok', 'conn_fail', 'ad_view', 'free_timeout')
         GROUP BY day, kind`,
        from,
      )
      .toArray();

    const daily = [];
    for (let i = days - 1; i >= 0; i--) {
      const d = dayOf(now - i * 86400000);
      daily.push({ day: d, active: 0, connected: 0, connects: 0, fails: 0, adViews: 0, freeTimeouts: 0 });
    }
    const byDay = new Map(daily.map((x) => [x.day, x]));
    for (const r of dayRows) {
      const x = byDay.get(r.day);
      if (x) {
        x.active = Number(r.active) || 0;
        x.connected = Number(r.connected) || 0;
      }
    }
    for (const r of connRows) {
      const x = byDay.get(r.day);
      if (!x) continue;
      const n = Number(r.n) || 0;
      if (r.kind === "conn_ok") x.connects = n;
      else if (r.kind === "conn_fail") x.fails = n;
      else if (r.kind === "ad_view") x.adViews = n;
      else if (r.kind === "free_timeout") x.freeTimeouts = n;
    }

    const adRows = this.sql
      .exec(
        `SELECT key, kind, SUM(value) AS n,
                SUM(CASE WHEN day = ? THEN value ELSE 0 END) AS today
         FROM counters WHERE day >= ? AND kind IN ('ad_view', 'ad_click', 'ad_done')
         GROUP BY key, kind`,
        today,
        from,
      )
      .toArray();
    const ads = new Map();
    for (const r of adRows) {
      const a = ads.get(r.key) || { id: r.key, views: 0, clicks: 0, completes: 0, viewsToday: 0 };
      const n = Number(r.n) || 0;
      if (r.kind === "ad_view") {
        a.views = n;
        a.viewsToday = Number(r.today) || 0;
      } else if (r.kind === "ad_click") a.clicks = n;
      else a.completes = n;
      ads.set(r.key, a);
    }

    const serverRows = this.sql
      .exec(
        `SELECT key, kind, SUM(value) AS n FROM counters
         WHERE day >= ? AND kind IN ('conn_ok', 'conn_fail', 'rate_up', 'rate_down', 'minutes')
         GROUP BY key, kind`,
        from,
      )
      .toArray();
    const servers = new Map();
    for (const r of serverRows) {
      const s = servers.get(r.key) || { name: r.key, ok: 0, fail: 0, up: 0, down: 0, minutes: 0 };
      const n = Number(r.n) || 0;
      if (r.kind === "conn_ok") s.ok = n;
      else if (r.kind === "conn_fail") s.fail = n;
      else if (r.kind === "rate_up") s.up = n;
      else if (r.kind === "rate_down") s.down = n;
      else s.minutes = n;
      servers.set(r.key, s);
    }
    for (const s of servers.values()) {
      s.successRate = s.ok + s.fail > 0 ? Math.round((100 * s.ok) / (s.ok + s.fail)) : null;
      s.satisfaction = s.up + s.down > 0 ? Math.round((100 * s.up) / (s.up + s.down)) : null;
    }

    const totals = this.sql
      .exec(
        `SELECT COUNT(*) AS devices, SUM(pro) AS pro,
                SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) AS active7
         FROM devices`,
        now - 7 * 86400000,
      )
      .toArray()[0] || {};

    let up = 0;
    let down = 0;
    for (const s of servers.values()) {
      up += s.up;
      down += s.down;
    }
    const todayRow = byDay.get(today) || { active: 0, connected: 0 };

    return {
      generatedAt: now,
      days,
      online: this.onlineCount(),
      totalDevices: Number(totals.devices) || 0,
      proDevices: Number(totals.pro) || 0,
      active7: Number(totals.active7) || 0,
      today: { active: todayRow.active, connected: todayRow.connected },
      satisfaction: up + down > 0 ? Math.round((100 * up) / (up + down)) : null,
      daily,
      ads: [...ads.values()].sort((a, b) => b.views - a.views),
      servers: [...servers.values()]
        .filter((s) => s.name)
        .sort((a, b) => b.ok + b.fail - (a.ok + a.fail)),
    };
  }

  users(tier, offset, limit, q) {
    const lim = Math.max(1, Math.min(200, Math.trunc(limit) || 50));
    const off = Math.max(0, Math.trunc(offset) || 0);
    const where = [];
    const args = [];
    if (tier === "pro") where.push("pro = 1");
    else if (tier === "free") where.push("pro = 0");
    if (q) {
      where.push("(id LIKE ? OR model LIKE ?)");
      args.push(`%${q}%`, `%${q}%`);
    }
    const w = where.length ? `WHERE ${where.join(" AND ")}` : "";
    const total = Number(this.sql.exec(`SELECT COUNT(*) AS n FROM devices ${w}`, ...args).toArray()[0].n) || 0;
    const rows = this.sql
      .exec(
        `SELECT id, model, android, app_version, pro, first_seen, last_seen, last_connect, connects
         FROM devices ${w} ORDER BY last_seen DESC LIMIT ? OFFSET ?`,
        ...args,
        lim,
        off,
      )
      .toArray();
    return {
      total,
      offset: off,
      users: rows.map((r) => ({
        id: r.id,
        model: r.model,
        android: r.android,
        appVersion: Number(r.app_version) || 0,
        pro: !!r.pro,
        firstSeen: Number(r.first_seen) || 0,
        lastSeen: Number(r.last_seen) || 0,
        lastConnect: Number(r.last_connect) || 0,
        connects: Number(r.connects) || 0,
      })),
    };
  }
}
