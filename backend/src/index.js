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
/** Extracts a readable Zarinpal error (v4 returns `errors` as an object or an array). */
function zpError(data) {
  const e = data && data.errors;
  if (Array.isArray(e) && e.length) return `کد ${e[0].code}: ${e[0].message || ""}`;
  if (e && typeof e === "object" && (e.code || e.message)) {
    let m = `کد ${e.code}: ${e.message || ""}`;
    if (Array.isArray(e.validations) && e.validations.length) {
      m += " (" + e.validations.map((v) => Object.entries(v).map(([k, val]) => `${k}: ${val}`).join(" ")).join("؛ ") + ")";
    }
    return m;
  }
  if (data && data.data && data.data.message) return data.data.message;
  return "ایجاد تراکنش ناموفق بود.";
}
/** True when the configured gateway is AqayePardakht (various spellings). */
function isAqaye(g) {
  const s = String(g || "").toLowerCase();
  return s.includes("aqaye") || s.includes("aghaye") || s.includes("aqayepardakht") || s.includes("aghapardakht") || s.includes("آقای");
}
/** Maps an AqayePardakht error code to a readable Persian message. */
function aqayeError(code) {
  const m = {
    "-1": "مبلغ خالی است", "-2": "کد درگاه (pin) خالی است", "-3": "آدرس بازگشت خالی است",
    "-4": "مبلغ عددی نیست", "-5": "مبلغ باید بین ۱۰۰ تا ۱۰۰٬۰۰۰٬۰۰۰ تومان باشد",
    "-6": "کد درگاه (pin) نادرست است", "-7": "کد تراکنش خالی است", "-8": "تراکنش پیدا نشد",
    "-9": "کد درگاه با این تراکنش هم‌خوانی ندارد", "-10": "مبلغ با تراکنش هم‌خوانی ندارد",
    "-11": "درگاه در انتظار تأیید یا غیرفعال است", "-12": "این درگاه اجازهٔ تراکنش ندارد",
    "-13": "شمارهٔ کارت باید ۱۶ رقمی باشد", "-14": "این درگاه قبلاً برای سایت دیگری استفاده شده است",
  };
  return m[String(code)] || `کد خطای ${code}`;
}
/** Reads POST params from a request body (JSON or url-encoded form). */
async function readForm(request) {
  try {
    if (!request || request.method === "GET") return {};
    const ct = (request.headers.get("content-type") || "").toLowerCase();
    if (ct.includes("application/json")) return await request.json();
    const text = await request.text();
    const out = {};
    for (const [k, v] of new URLSearchParams(text)) out[k] = v;
    return out;
  } catch (e) {
    return {};
  }
}
/** Lowercase hex HMAC-SHA256 of `message` with `secret` (for webhook signature checks). */
async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
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
.cbox{margin-top:18px;border:1px solid var(--line);border-radius:18px;padding:16px;background:rgba(10,12,22,.4)}
.ctitle{font-weight:700;font-size:15px;margin-bottom:8px;text-align:center;color:#2fe3b8}
.addr{word-break:break-all;background:rgba(10,12,22,.7);border:1px dashed var(--b2);border-radius:12px;padding:12px;font-size:13px;direction:ltr;text-align:left;cursor:pointer;margin:8px 0}
</style><link href="https://cdn.jsdelivr.net/npm/vazirmatn@33.0.3/Vazirmatn-font-face.css" rel="stylesheet">`;

function buildBuyPage(origin, plans, crypto, card) {
  const planCards = plans.map((p, i) => `
    <div class="plan${i === 0 ? " sel" : ""}" data-id="${esc(p.id)}" onclick="pick(this)">
      <span class="r"></span>
      <span class="nm"><b>${esc(p.title)}</b><small>${faNum(p.days)} روز</small></span>
      <span class="pr">${esc(p.price || (faNum(p.amountToman.toLocaleString("en-US")) + " تومان"))}</span>
    </div>`).join("");
  const methodButtons = `
    ${card && card.enabled ? `<button class="btn" id="mcard" style="background:linear-gradient(90deg,#f3cf7a,#e0a92e);color:#1f1500" onclick="startOrder('card')">کارت‌به‌کارت</button>` : ``}
    ${crypto && crypto.enabled ? `<button class="btn" id="mcrypto" onclick="startOrder('crypto')">پرداخت با ارز دیجیتال</button>` : ``}`;
  let body;
  if (!plans.length) {
    body = `<p style="margin-top:20px">در حال حاضر پلنی برای خرید آنلاین تنظیم نشده است. لطفاً بعداً دوباره سر بزنید یا با پشتیبانی تماس بگیرید.</p>`;
  } else if (!(card && card.enabled) && !(crypto && crypto.enabled)) {
    body = `<div class="plans">${planCards}</div><p style="margin-top:16px">روش پرداختی هنوز توسط مدیر فعال نشده است.</p>`;
  } else {
    body = `<div class="plans">${planCards}</div>
       <label>نام کاربری یا ایمیل</label><input id="login" autocomplete="username" dir="ltr">
       <label>رمز عبور</label><input id="pass" type="password" autocomplete="current-password" dir="ltr">
       <div style="height:10px"></div>
       ${methodButtons}
       <div class="err" id="err"></div>`;
  }
  return `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>خرید اشتراک — FAIROS VPN</title>${PAGE_CSS}</head><body><div class="wrap"><div class="card" id="card">
<div id="stage1">
<p class="logo">FAIROS<small> VPN</small></p>
<h1>خرید اشتراک پرو</h1>
<p>یک پلن را انتخاب کنید، با حساب خودتان وارد شوید و روش پرداخت را بزنید. برای واریز ۳۰ دقیقه وقت دارید و اشتراک پس از تأیید مدیر فعال می‌شود.</p>
${body}
<div class="foot">FAIROS VPN</div>
</div>
<div id="stage2" style="display:none"></div>
</div></div>
<script>
let sel=document.querySelector(".plan.sel")?.dataset.id||"";
let order=null,timer=null;
function pick(el){document.querySelectorAll(".plan").forEach(p=>p.classList.remove("sel"));el.classList.add("sel");sel=el.dataset.id;}
function creds(){const l=document.getElementById("login").value.trim();const p=document.getElementById("pass").value;const e=document.getElementById("err");e.textContent="";if(!l||!p){e.textContent="نام کاربری و رمز را وارد کنید.";return null;}if(!sel){e.textContent="یک پلن انتخاب کنید.";return null;}return {login:l,password:p,planId:sel};}
function cp(t){try{navigator.clipboard.writeText(String(t).trim());}catch(e){}}
function fmtToman(n){try{return Number(n).toLocaleString("en-US")+" تومان";}catch(e){return n+" تومان";}}
function mmss(ms){const s=Math.max(0,Math.floor(ms/1000));return String(Math.floor(s/60)).padStart(2,"0")+":"+String(s%60).padStart(2,"0");}
async function startOrder(method){
  const c=creds();if(!c)return;const err=document.getElementById("err");
  const btn=document.getElementById(method==='card'?"mcard":"mcrypto");if(btn){btn.disabled=true;btn.textContent="در حال ساخت سفارش…";}
  try{
    const r=await fetch("/pay/order/create",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({...c,method})});
    const d=await r.json();
    if(!r.ok||!d.ok){err.textContent=d.error||"خطا در ساخت سفارش.";if(btn){btn.disabled=false;btn.textContent=(method==='card'?"کارت‌به‌کارت":"پرداخت با ارز دیجیتال");}return;}
    order=d;showStage2();
  }catch(e){err.textContent="ارتباط با سرور برقرار نشد.";if(btn){btn.disabled=false;btn.textContent=(method==='card'?"کارت‌به‌کارت":"پرداخت با ارز دیجیتال");}}
}
function showStage2(){
  document.getElementById("stage1").style.display="none";
  const s=document.getElementById("stage2");s.style.display="block";
  const isCard=order.method==='card';
  let dest="";
  if(isCard&&order.card){dest='<div class="addr" onclick="cp(\\''+order.card.number+'\\')" title="برای کپی ضربه بزنید">'+order.card.number+'</div>'+(order.card.holder?'<div style="text-align:center;font-size:14px;margin:6px 0">'+order.card.holder+'</div>':'')+(order.card.note?'<p style="text-align:right">'+order.card.note+'</p>':'');}
  if(!isCard&&order.crypto){dest=(order.crypto.addresses||[]).map(function(a){return (a.label?'<div style="font-size:12px;color:var(--mute);text-align:right;margin-top:8px">'+a.label+'</div>':'')+'<div class="addr" onclick="cp(\\''+a.address+'\\')" title="برای کپی ضربه بزنید">'+a.address+'</div>';}).join('')+(order.crypto.note?'<p style="text-align:right">'+order.crypto.note+'</p>':'');}
  const last6Field=isCard?'<label>۶ رقم آخر کارت مبدأ</label><input id="last6" dir="ltr" inputmode="numeric" maxlength="6" placeholder="------">':'';
  s.innerHTML='<p class="logo">FAIROS<small> VPN</small></p>'
    +'<h1>'+(isCard?'واریز کارت‌به‌کارت':'پرداخت ارز دیجیتال')+'</h1>'
    +'<div class="cbox"><div style="text-align:center;font-size:13px;color:var(--mute)">مبلغ قابل پرداخت</div><div style="text-align:center;font-size:20px;font-weight:bold;margin:4px 0">'+fmtToman(order.amount)+'</div>'
    +'<div style="text-align:center;margin:8px 0">مهلت پرداخت: <b id="cd" style="color:#f3cf7a">--:--</b></div>'+dest+'</div>'
    +'<div class="cbox"><p style="text-align:right">بعد از واریز، اطلاعات زیر را وارد و ثبت کنید:</p>'
    +last6Field
    +'<label>'+(isCard?'کد پیگیری/رهگیری واریز':'کد تراکنش (TxID)')+'</label><input id="trackRef" dir="ltr" placeholder="'+(isCard?'کد رهگیری':'hash تراکنش')+'">'
    +'<label>زمان تقریبی واریز (اختیاری)</label><input id="paidApprox" dir="ltr" placeholder="مثلاً 14:30">'
    +'<label>تصویر رسید (اختیاری)</label><input id="receipt" type="file" accept="image/*">'
    +'<button class="btn" id="sgo" onclick="submitOrder()">ثبت پرداخت</button></div>'
    +'<div class="err" id="err2"></div>'
    +'<div class="foot">شماره سفارش: '+order.orderId+'</div>';
  tick();timer=setInterval(tick,1000);
}
async function tick(){
  if(!order)return;
  try{
    const r=await fetch("/pay/order/status?id="+order.orderId+"&token="+encodeURIComponent(order.token));
    const d=await r.json();
    const cd=document.getElementById("cd");
    if(d.status==="EXPIRED"){clearInterval(timer);if(cd)cd.textContent="منقضی شد";expired();return;}
    if(cd)cd.textContent=mmss(d.remainingMs);
  }catch(e){}
}
function expired(){
  const s=document.getElementById("stage2");
  s.innerHTML='<p class="logo">FAIROS<small> VPN</small></p><div class="badge" style="border-color:#ff5c7a;color:#ff5c7a">!</div><h1>مهلت تمام شد</h1><p>مهلت ۳۰ دقیقه‌ای این سفارش به پایان رسید و دیگر فعال نیست. برای خرید دوباره یک سفارش جدید بسازید.</p><button class="btn" onclick="location.reload()">سفارش جدید</button>';
}
async function submitOrder(){
  const err=document.getElementById("err2");err.textContent="";
  const isCard=order.method==='card';
  const trackRef=document.getElementById("trackRef").value.trim();
  if(!trackRef){err.textContent=(isCard?"کد پیگیری را وارد کنید.":"کد تراکنش را وارد کنید.");return;}
  const last6=isCard?(document.getElementById("last6").value.replace(/\\D/g,"")):"";
  if(isCard&&last6.length!==6){err.textContent="۶ رقم آخر کارت مبدأ را کامل وارد کنید.";return;}
  const paidApprox=document.getElementById("paidApprox").value.trim();
  const btn=document.getElementById("sgo");btn.disabled=true;btn.textContent="در حال ثبت…";
  let receipt="";
  const f=document.getElementById("receipt").files[0];
  if(f){if(f.size>400000){err.textContent="تصویر رسید باید کمتر از ۴۰۰ کیلوبایت باشد.";btn.disabled=false;btn.textContent="ثبت پرداخت";return;}receipt=await new Promise(function(res){const rd=new FileReader();rd.onload=function(){res(rd.result);};rd.onerror=function(){res("");};rd.readAsDataURL(f);});}
  try{
    const r=await fetch("/pay/order/submit",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({orderId:order.orderId,token:order.token,trackRef:trackRef,last6:last6,paidApprox:paidApprox,receipt:receipt})});
    const d=await r.json();
    if(r.ok&&d.ok){clearInterval(timer);document.getElementById("stage2").innerHTML='<p class="logo">FAIROS<small> VPN</small></p><div class="badge" style="border-color:#2fe3b8;color:#2fe3b8">✓</div><h1>ثبت شد</h1><p>اطلاعات پرداخت شما ثبت شد و در صف بررسی مدیر است. پس از تأیید، اشتراک پرو روی حسابتان فعال می‌شود.</p>';return;}
    err.textContent=d.error||"خطا در ثبت پرداخت.";btn.disabled=false;btn.textContent="ثبت پرداخت";
  }catch(e){err.textContent="ارتباط با سرور برقرار نشد.";btn.disabled=false;btn.textContent="ثبت پرداخت";}
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
    if (path === "/") return json({ ok: true, service: "fairosvpn", build: "20261027-welcome" });
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
    this.adWatchers = new Map(); // device -> ms expiry, live "watching an ad now" gauge
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
    // All-time per-ad view/click totals (never purged) — the reliable basis for a view cap.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS ad_totals (
      ad_id TEXT PRIMARY KEY, views INTEGER DEFAULT 0, clicks INTEGER DEFAULT 0)`);
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
        case "/config": return json(this.configWithAdViews(await this.getConfig()));
        case "/events":
          if (request.method !== "POST") return json({ error: "method" }, 405);
          return this.handleEvents(await body());
        case "/notices": return json({ notices: A.listNotices(sql) });
        case "/notifications/broadcasts": return json({ notices: A.listUserBroadcasts(sql) });
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
        case "/auth/google": return json(await this.authGoogle(await body()));
        case "/auth/google-config": return json({ clientId: (this.env.GOOGLE_CLIENT_ID || A.kvGet(sql, "google_client_id", "")).trim() });

        // ---- profile (logged-in user)
        case "/me": { const { acc } = await user(); return json(A.getMe(sql, acc)); }
        case "/me/password": { const { acc } = await user(); return json(await A.changePassword(sql, acc, await body())); }
        case "/me/display-name": { const { acc } = await user(); return json(A.setDisplayName(sql, acc, await body())); }
        case "/me/avatar": { const { acc } = await user(); return json(A.setAvatar(sql, acc, await body())); }
        case "/me/device/remove": { const { acc } = await user(); return json(A.removeDevice(sql, acc, await body())); }
        case "/me/mission/claim": { const { acc } = await user(); return json(A.claimMission(sql, acc, await body())); }
        case "/me/redeem": { const { acc } = await user(); return json(A.redeemPlan(sql, acc, await body())); }
        case "/me/redeem-invite": { const { acc } = await user(); return json(A.redeemInvite(sql, acc, await body())); }
        case "/me/avatar-preset": { const { acc } = await user(); return json(A.setAvatarPreset(sql, acc, await body())); }

        // ---- admin: config + stats
        case "/admin/ping": await admin(); return json({ ok: true, online: this.onlineCount() });
        case "/admin/config":
          await admin();
          if (request.method === "PUT" || request.method === "POST") return this.putConfig(await body());
          return json(this.configWithAdViews(await this.getConfig()));
        case "/admin/summary": await admin(); return json(this.summary(Number(qp.get("days") || 14)));
        case "/admin/ad-reset-views": {
          await admin();
          const id = str((await body()).id, 60);
          if (id) this.sql.exec("UPDATE ad_totals SET views = 0 WHERE ad_id = ?", id);
          return json({ ok: true, id, views: 0 });
        }
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

        // ---- admin: instant broadcast to all users + admin alerts (payments, etc.)
        case "/admin/broadcast": {
          await admin();
          const b = await body();
          const res = A.adminBroadcast(sql, b);
          // Nudge every connected app to pop a system notification immediately.
          this.broadcast(JSON.stringify({
            type: "notice",
            title: String(b.title || "").slice(0, 120),
            body: String(b.body || "").slice(0, 1000),
          }));
          return json(res);
        }
        case "/admin/alerts": await admin(); return json(A.listAdminAlerts(sql));
        case "/admin/alerts/seen": await admin(); return json(A.markAdminAlertsSeen(sql));

        // ---- pro servers (names are public; links are secret and go only to paying users)
        case "/pro/showcase": return json({ names: A.proShowcase(sql) });
        case "/admin/pro-servers": await admin(); return json({ servers: A.adminListProServers(sql) });
        case "/admin/pro-server": await admin(); return json(A.adminSaveProServer(sql, await body()));
        case "/admin/pro-server/delete": await admin(); return json(A.adminDeleteProServer(sql, (await body()).id));

        // ---- purchase website
        case "/buy": return this.buyPage(url.origin);
        case "/pay/start": return json(await this.payStart(await body(), url.origin));
        case "/pay/crypto": return json(await this.payCrypto(await body()));
        // Two-stage manual payment: open a 30-minute order, then submit what was paid.
        case "/pay/order/create": return json(await this.payOrderCreate(await body()));
        case "/pay/order/submit": return json(A.submitOrder(sql, await body()));
        case "/pay/order/status": return json(A.orderStatus(sql, qp.get("id") || "", qp.get("token") || ""));
        case "/pay/beyounger": return json(await this.payBeyounger(await body(), url.origin));
        case "/pay/beyounger-webhook": return this.beyWebhook(request);
        case "/pay/beyounger-return": return this.beyReturn(url);
        case "/pay/callback": return this.payCallback(request, url);

        // ---- crypto / card orders (admin review)
        case "/admin/crypto-orders": await admin(); return json({ orders: A.adminListCryptoOrders(sql, qp.get("status") || "") });
        case "/admin/crypto-order": await admin(); { const b = await body(); b.actor = (this.env.ADMIN_USER || "admin"); return json(A.adminDecideCryptoOrder(sql, b)); }
        case "/admin/crypto-order/receipt": {
          await admin();
          const data = A.getOrderReceipt(sql, qp.get("id") || "");
          if (!data) return json({ error: "not found" }, 404);
          const m = /^data:(image\/[a-z+.-]+);base64,(.*)$/i.exec(data);
          if (!m) return json({ error: "bad" }, 500);
          const bin = Uint8Array.from(atob(m[2]), (c) => c.charCodeAt(0));
          return new Response(bin, { headers: { "Content-Type": m[1], "Cache-Control": "no-store", ...CORS } });
        }
        case "/admin/audit": await admin(); return json({ logs: A.adminListAuditLogs(sql, Number(qp.get("limit") || 100)) });

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
      if (typeof b.cryptoEnabled === "boolean") A.kvSet(this.sql, "crypto_enabled", b.cryptoEnabled ? "1" : "0");
      if (typeof b.cryptoAsset === "string") A.kvSet(this.sql, "crypto_asset", b.cryptoAsset.trim().slice(0, 60));
      if (typeof b.cryptoAddress === "string") A.kvSet(this.sql, "crypto_address", b.cryptoAddress.trim().slice(0, 200));
      if (typeof b.cryptoAddresses === "string") A.kvSet(this.sql, "crypto_addresses", b.cryptoAddresses.slice(0, 2000));
      if (typeof b.cryptoNote === "string") A.kvSet(this.sql, "crypto_note", b.cryptoNote.trim().slice(0, 300));
      if (typeof b.cardEnabled === "boolean") A.kvSet(this.sql, "card_enabled", b.cardEnabled ? "1" : "0");
      if (typeof b.cardNumber === "string") A.kvSet(this.sql, "card_number", b.cardNumber.trim().slice(0, 40));
      if (typeof b.cardHolder === "string") A.kvSet(this.sql, "card_holder", b.cardHolder.trim().slice(0, 80));
      if (typeof b.cardNote === "string") A.kvSet(this.sql, "card_note", b.cardNote.trim().slice(0, 300));
      if (typeof b.googleClientId === "string") A.kvSet(this.sql, "google_client_id", b.googleClientId.trim().slice(0, 200));
      if (b.invitePointsInviter !== undefined) A.kvSet(this.sql, "invite_points_inviter", String(Math.max(0, Math.trunc(Number(b.invitePointsInviter) || 0))));
      if (b.invitePointsInvitee !== undefined) A.kvSet(this.sql, "invite_points_invitee", String(Math.max(0, Math.trunc(Number(b.invitePointsInvitee) || 0))));
      if (typeof b.welcomeEnabled === "boolean") A.kvSet(this.sql, "welcome_enabled", b.welcomeEnabled ? "1" : "0");
      if (b.welcomeDays !== undefined) A.kvSet(this.sql, "welcome_days", String(Math.max(0, Math.min(365, Math.trunc(Number(b.welcomeDays) || 0)))));
      if (typeof b.beyEnabled === "boolean") A.kvSet(this.sql, "bey_enabled", b.beyEnabled ? "1" : "0");
      // API key and webhook secret are write-only: a blank value keeps the existing one.
      if (typeof b.beyApiKey === "string" && b.beyApiKey.trim()) A.kvSet(this.sql, "bey_api_key", b.beyApiKey.trim().slice(0, 2000));
      if (typeof b.beyWebhookId === "string") A.kvSet(this.sql, "bey_webhook_id", b.beyWebhookId.trim().slice(0, 120));
      if (typeof b.beyWebhookSecret === "string" && b.beyWebhookSecret.trim()) A.kvSet(this.sql, "bey_webhook_secret", b.beyWebhookSecret.trim().slice(0, 400));
    }
    const beyKey = A.kvGet(this.sql, "bey_api_key", "");
    return {
      merchantId: A.kvGet(this.sql, "merchant_id", ""),
      gateway: A.kvGet(this.sql, "gateway", "zarinpal"),
      sandbox: A.kvGet(this.sql, "sandbox", "0") === "1",
      buyUrl: A.kvGet(this.sql, "buy_url", ""),
      cryptoEnabled: A.kvGet(this.sql, "crypto_enabled", "0") === "1",
      cryptoAsset: A.kvGet(this.sql, "crypto_asset", ""),
      cryptoAddress: A.kvGet(this.sql, "crypto_address", ""),
      cryptoAddresses: A.kvGet(this.sql, "crypto_addresses", ""),
      cryptoNote: A.kvGet(this.sql, "crypto_note", ""),
      cardEnabled: A.kvGet(this.sql, "card_enabled", "0") === "1",
      cardNumber: A.kvGet(this.sql, "card_number", ""),
      cardHolder: A.kvGet(this.sql, "card_holder", ""),
      cardNote: A.kvGet(this.sql, "card_note", ""),
      googleClientId: A.kvGet(this.sql, "google_client_id", ""),
      invitePointsInviter: Number(A.kvGet(this.sql, "invite_points_inviter", "0")),
      invitePointsInvitee: Number(A.kvGet(this.sql, "invite_points_invitee", "0")),
      welcomeEnabled: A.kvGet(this.sql, "welcome_enabled", "1") === "1",
      welcomeDays: Number(A.kvGet(this.sql, "welcome_days", "3")),
      beyEnabled: A.kvGet(this.sql, "bey_enabled", "0") === "1",
      // Never send the full API key back; just whether one is set.
      beyApiKeySet: !!beyKey,
      beyWebhookId: A.kvGet(this.sql, "bey_webhook_id", ""),
      beyWebhookSecretSet: !!A.kvGet(this.sql, "bey_webhook_secret", ""),
    };
  }

  // ---------- purchase website + payment gateways (Zarinpal and AqayePardakht)

  gateway() { return A.kvGet(this.sql, "gateway", "zarinpal"); }
  sandboxOn() { return A.kvGet(this.sql, "sandbox", "0") === "1"; }
  zpBase() { return this.sandboxOn() ? "https://sandbox.zarinpal.com" : "https://payment.zarinpal.com"; }

  /** Verifies the buyer, creates a payment at the configured gateway and returns the redirect URL. */
  async payStart(body, origin) {
    const acc = await A.findForPurchase(this.sql, body); // throws 401 on bad credentials
    const plan = A.getPlan(this.sql, body.planId);
    if (!plan || !plan.enabled) throw new A.ApiError(404, "پلن انتخابی پیدا نشد.");
    const amountToman = Math.trunc(Number(plan.amount_toman || 0));
    if (amountToman <= 0) throw new A.ApiError(400, "این پلن برای خرید آنلاین تنظیم نشده است. با پشتیبانی تماس بگیرید.");
    const merchantId = A.kvGet(this.sql, "merchant_id", "");
    if (!merchantId) throw new A.ApiError(503, "درگاه پرداخت هنوز توسط مدیر تنظیم نشده است.");
    const callback = `${origin}/pay/callback`;
    if (isAqaye(this.gateway())) return this.payStartAqaye(acc, plan, amountToman, merchantId, callback);
    return this.payStartZarinpal(acc, plan, amountToman, merchantId, callback);
  }

  async payStartZarinpal(acc, plan, amountToman, merchantId, callback) {
    const amountRial = amountToman * 10;
    const r = await fetch(`${this.zpBase()}/pg/v4/payment/request.json`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        merchant_id: merchantId, amount: amountRial, callback_url: callback,
        description: `${plan.title} — FAIROS VPN`, metadata: { email: acc.email },
      }),
    });
    const data = await r.json().catch(() => ({}));
    if (data?.data?.code !== 100 || !data?.data?.authority) {
      throw new A.ApiError(502, `خطای درگاه پرداخت: ${zpError(data)}`);
    }
    const authority = data.data.authority;
    A.createPayment(this.sql, { authority, accountId: acc.id, planId: plan.id, amount: amountRial });
    return { ok: true, redirect: `${this.zpBase()}/pg/StartPay/${authority}` };
  }

  async payStartAqaye(acc, plan, amountToman, pin, callback) {
    const r = await fetch("https://panel.aqayepardakht.ir/api/v2/create", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ pin, amount: amountToman, callback, description: `${plan.title} — FAIROS VPN`, email: acc.email }),
    });
    const data = await r.json().catch(() => ({}));
    if (data?.status !== "success" || !data?.transid) {
      throw new A.ApiError(502, `خطای درگاه پرداخت: ${aqayeError(data?.code)}`);
    }
    const transid = String(data.transid);
    // AqayePardakht works in Toman, so the stored amount (used again at verify) is in Toman.
    A.createPayment(this.sql, { authority: transid, accountId: acc.id, planId: plan.id, amount: amountToman });
    const base = this.sandboxOn() ? "https://panel.aqayepardakht.ir/startpay/sandbox" : "https://panel.aqayepardakht.ir/startpay";
    return { ok: true, redirect: `${base}/${transid}` };
  }

  /** Gateway return URL. Zarinpal uses GET ?Authority&Status; AqayePardakht POSTs transid/status. */
  async payCallback(request, url) {
    const zAuth = url.searchParams.get("Authority") || "";
    if (zAuth) return this.payCallbackZarinpal(zAuth, url.searchParams.get("Status") || "");
    const form = await readForm(request);
    const transid = String(form.transid || url.searchParams.get("transid") || "");
    const status = String(form.status != null ? form.status : (url.searchParams.get("status") || ""));
    if (transid) return this.payCallbackAqaye(transid, status);
    return this.resultPage(false, "اطلاعات بازگشت از درگاه ناقص بود.");
  }

  async payCallbackZarinpal(authority, status) {
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

  async payCallbackAqaye(transid, status) {
    const pay = A.getPayment(this.sql, transid);
    if (!pay) return this.resultPage(false, "تراکنش پیدا نشد.");
    if (pay.status === "paid") return this.resultPage(true, "این پرداخت قبلاً ثبت شده و اشتراک شما فعال است.", pay.ref_id);
    if (status !== "1") { A.markFailed(this.sql, transid); return this.resultPage(false, "پرداخت توسط شما لغو شد یا ناموفق بود."); }
    const pin = A.kvGet(this.sql, "merchant_id", "");
    try {
      const r = await fetch("https://panel.aqayepardakht.ir/api/v2/verify", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ pin, amount: Number(pay.amount), transid }),
      });
      const data = await r.json().catch(() => ({}));
      const ok = data?.status === "success" && (String(data?.code) === "1" || String(data?.code) === "2");
      if (ok) {
        A.markPaid(this.sql, transid, transid);
        const plan = A.getPlan(this.sql, pay.plan_id);
        if (plan) A.grantPlan(this.sql, pay.account_id, { days: plan.days });
        return this.resultPage(true, "پرداخت با موفقیت انجام شد و اشتراک پرو شما همین حالا فعال شد.", transid);
      }
      A.markFailed(this.sql, transid);
      return this.resultPage(false, `تأیید پرداخت ناموفق بود: ${aqayeError(data?.code)}. اگر مبلغی کسر شده تا ۷۲ ساعت برمی‌گردد.`);
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
    const bank = { enabled: !!A.kvGet(this.sql, "merchant_id", "") };
    // Crypto addresses: one per line "Label = Address" (fall back to the legacy single address).
    let addresses = A.kvGet(this.sql, "crypto_addresses", "").split("\n").map((s) => s.trim()).filter(Boolean).map((ln) => {
      const i = ln.indexOf("=");
      return i > 0 ? { label: ln.slice(0, i).trim(), address: ln.slice(i + 1).trim() } : { label: "", address: ln };
    }).filter((a) => a.address);
    if (!addresses.length) {
      const legacy = A.kvGet(this.sql, "crypto_address", "");
      if (legacy) addresses = [{ label: A.kvGet(this.sql, "crypto_asset", ""), address: legacy }];
    }
    const crypto = {
      enabled: A.kvGet(this.sql, "crypto_enabled", "0") === "1" && addresses.length > 0,
      addresses,
      note: A.kvGet(this.sql, "crypto_note", ""),
    };
    const card = {
      enabled: A.kvGet(this.sql, "card_enabled", "0") === "1" && !!A.kvGet(this.sql, "card_number", ""),
      number: A.kvGet(this.sql, "card_number", ""),
      holder: A.kvGet(this.sql, "card_holder", ""),
      note: A.kvGet(this.sql, "card_note", ""),
    };
    const html = buildBuyPage(origin, plans, crypto, card);
    return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", ...CORS } });
  }

  /** Verifies a Google ID token with Google and returns the verified profile, or throws. */
  async verifyGoogleIdToken(idToken) {
    const clientId = (this.env.GOOGLE_CLIENT_ID || A.kvGet(this.sql, "google_client_id", "")).trim();
    if (!clientId) throw new A.ApiError(503, "ورود با گوگل هنوز تنظیم نشده است.");
    if (!idToken) throw new A.ApiError(400, "توکن گوگل ارسال نشد.");
    let t;
    try {
      const r = await fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken));
      if (!r.ok) throw new Error("bad");
      t = await r.json();
    } catch (e) {
      throw new A.ApiError(401, "توکن گوگل معتبر نیست.");
    }
    // Enforce audience, issuer, expiry and verified email on the returned claims.
    return A.validateGoogleClaims(t, clientId);
  }

  /** Public: sign in (or auto-register) with a Google ID token. */
  async authGoogle(body) {
    const profile = await this.verifyGoogleIdToken(String(body.idToken || ""));
    return await A.googleUpsertLogin(this.sql, profile, body);
  }

  /** Public: records a crypto payment the user claims to have made (pending admin review). Legacy one-step. */
  async payCrypto(body) {
    const acc = await A.findForPurchase(this.sql, body); // throws 401 on bad credentials
    return A.createCryptoOrder(this.sql, acc, body);
  }

  /** Reads the admin's crypto addresses as [{label,address}] (legacy single-address supported). */
  cryptoAddresses() {
    let addresses = A.kvGet(this.sql, "crypto_addresses", "").split("\n").map((s) => s.trim()).filter(Boolean).map((ln) => {
      const i = ln.indexOf("=");
      return i > 0 ? { label: ln.slice(0, i).trim(), address: ln.slice(i + 1).trim() } : { label: "", address: ln };
    }).filter((a) => a.address);
    if (!addresses.length) {
      const legacy = A.kvGet(this.sql, "crypto_address", "");
      if (legacy) addresses = [{ label: A.kvGet(this.sql, "crypto_asset", ""), address: legacy }];
    }
    return addresses;
  }

  /** Stage 1: verify the buyer and open a 30-minute order, returning the destination to pay. */
  async payOrderCreate(body) {
    const acc = await A.findForPurchase(this.sql, body); // throws 401 on bad credentials
    const method = body.method === "card" ? "card" : "crypto";
    if (method === "card") {
      const cardEnabled = A.kvGet(this.sql, "card_enabled", "0") === "1";
      const cardNumber = A.kvGet(this.sql, "card_number", "");
      if (!cardEnabled || !cardNumber) throw new A.ApiError(400, "پرداخت کارت‌به‌کارت فعال نیست.");
      const out = A.createOrder(this.sql, acc, { planId: body.planId, method, cardNumber, cardHolder: A.kvGet(this.sql, "card_holder", "") });
      out.card = { number: cardNumber, holder: A.kvGet(this.sql, "card_holder", ""), note: A.kvGet(this.sql, "card_note", "") };
      return out;
    }
    const addresses = this.cryptoAddresses();
    const cryptoEnabled = A.kvGet(this.sql, "crypto_enabled", "0") === "1";
    if (!cryptoEnabled || !addresses.length) throw new A.ApiError(400, "پرداخت کریپتو فعال نیست.");
    const out = A.createOrder(this.sql, acc, { planId: body.planId, method });
    out.crypto = { addresses, note: A.kvGet(this.sql, "crypto_note", "") };
    return out;
  }

  // ---------- Beyounger hosted crypto checkout (automatic, via webhook) ----------

  /** Verifies the buyer, creates a Beyounger acquiring order and returns the hosted checkout URL. */
  async payBeyounger(body, origin) {
    const acc = await A.findForPurchase(this.sql, body); // throws 401 on bad credentials
    const plan = A.getPlan(this.sql, body.planId);
    if (!plan || !plan.enabled) throw new A.ApiError(404, "پلن انتخابی پیدا نشد.");
    const apiKey = A.kvGet(this.sql, "bey_api_key", "");
    const webhookId = A.kvGet(this.sql, "bey_webhook_id", "");
    if (!apiKey || !webhookId) throw new A.ApiError(503, "پرداخت ارزی (Beyounger) هنوز توسط مدیر کامل تنظیم نشده است.");
    const amountUsd = Number(plan.amount_usd || 0);
    if (!(amountUsd > 0)) throw new A.ApiError(400, "قیمت دلاری این پلن تنظیم نشده است. با پشتیبانی تماس بگیرید.");
    const outTradeNo = "fv_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    const r = await fetch("https://app.beyounger.com/payment/acquiring/orders", {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        out_trade_no: outTradeNo,
        order_currency: "USD",
        order_amount: amountUsd,
        expire_seconds: 1800,
        underpay_tolerance: 0,
        notify_webhook_id: webhookId,
        redirect_url: `${origin}/pay/beyounger-return?ref=${outTradeNo}`,
        remark: `${plan.title} — FAIROS VPN`,
      }),
    });
    const data = await r.json().catch(() => ({}));
    const order = data && data.data && data.data.order;
    if (!order || !order.checkout_url || !order.order_id) {
      const msg = (data && (data.message || data.msg || (data.error && (data.error.message || data.error)))) || "ایجاد پرداخت ارزی ناموفق بود.";
      throw new A.ApiError(502, `خطای درگاه ارزی: ${msg}`);
    }
    A.createPayment(this.sql, { authority: outTradeNo, accountId: acc.id, planId: plan.id, amount: amountUsd, providerRef: String(order.order_id) });
    return { ok: true, redirect: order.checkout_url };
  }

  /** Beyounger webhook: marks the order paid and activates pro when status=completed. */
  async beyWebhook(request) {
    const raw = await request.text().catch(() => "");
    const secret = A.kvGet(this.sql, "bey_webhook_secret", "");
    if (secret) {
      const sig = request.headers.get("X-Beyounger-Signature") || request.headers.get("x-beyounger-signature") || "";
      const expected = await hmacHex(secret, raw);
      if (!sig || !timingSafeEqual(sig.trim().toLowerCase(), expected)) {
        return json({ error: "bad signature" }, 401);
      }
    }
    let evt = {};
    try { evt = JSON.parse(raw); } catch (e) { return json({ error: "bad json" }, 400); }
    const d = evt.data || {};
    const status = String(d.status || "");
    const outTradeNo = String(d.out_trade_no || "");
    const orderId = String(d.order_id || d.business_id || "");
    if (status === "completed") {
      const pay = (outTradeNo && A.getPayment(this.sql, outTradeNo)) || (orderId && A.getPaymentByProviderRef(this.sql, orderId));
      if (pay && pay.status !== "paid") {
        A.markPaid(this.sql, pay.authority, orderId || outTradeNo);
        const plan = A.getPlan(this.sql, pay.plan_id);
        if (plan) A.grantPlan(this.sql, pay.account_id, { days: plan.days });
      }
    }
    return json({ ok: true });
  }

  /** The page the buyer returns to from Beyounger; double-checks status so pro activates even if the webhook is late. */
  async beyReturn(url) {
    const ref = url.searchParams.get("ref") || "";
    const pay = ref ? A.getPayment(this.sql, ref) : null;
    if (!pay) return this.resultPage(false, "تراکنش پیدا نشد.");
    if (pay.status === "paid") return this.resultPage(true, "پرداخت شما کامل شد و اشتراک پرو فعال است.", pay.ref_id);
    const apiKey = A.kvGet(this.sql, "bey_api_key", "");
    try {
      const r = await fetch(`https://app.beyounger.com/payment/acquiring/orders/${encodeURIComponent(pay.provider_ref)}`, {
        headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" },
      });
      const data = await r.json().catch(() => ({}));
      const order = (data && data.data && (data.data.order || data.data)) || {};
      if (String(order.status) === "completed") {
        A.markPaid(this.sql, pay.authority, pay.provider_ref);
        const plan = A.getPlan(this.sql, pay.plan_id);
        if (plan) A.grantPlan(this.sql, pay.account_id, { days: plan.days });
        return this.resultPage(true, "پرداخت شما کامل شد و اشتراک پرو همین حالا فعال شد.", pay.provider_ref);
      }
    } catch (e) {
      // fall through to the pending message
    }
    return this.resultPage(true, "پرداخت شما ثبت شد. اگر کامل شده باشد، اشتراک تا دقایقی دیگر فعال می‌شود؛ بعد در برنامه «به‌روزرسانی» را بزنید.");
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

  /** All-time per-ad totals as a map {adId: {views, clicks}}. */
  adTotals() {
    const m = {};
    try {
      for (const r of this.sql.exec("SELECT ad_id, views, clicks FROM ad_totals").toArray()) {
        m[r.ad_id] = { views: Number(r.views || 0), clicks: Number(r.clicks || 0) };
      }
    } catch (e) { /* ignore */ }
    return m;
  }

  /** How many devices are watching an ad right now (prunes expired entries). */
  adWatchersNow(now = Date.now()) {
    for (const [d, exp] of this.adWatchers) if (exp <= now) this.adWatchers.delete(d);
    if (this.adWatchers.size > 50000) this.adWatchers.clear();
    return this.adWatchers.size;
  }

  /** A copy of the config with live per-ad view counts injected; never mutates the stored config. */
  configWithAdViews(cfg) {
    try {
      if (!cfg || !cfg.ads || !Array.isArray(cfg.ads.items)) return cfg;
      const totals = this.adTotals();
      const items = cfg.ads.items.map((it) => ({ ...it, views: (totals[it.id] && totals[it.id].views) || 0 }));
      return { ...cfg, ads: { ...cfg.ads, items } };
    } catch (e) {
      return cfg;
    }
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
          // Live gauge: this device is watching an ad right now (expires shortly).
          this.adWatchers.set(device, now + 45000);
          if (id) add(day, t, id);
          break;
        case "ad_click":
        case "ad_done":
          if (t === "ad_done") this.adWatchers.delete(device);
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
      // Keep an all-time per-ad total (counters get purged; this does not), for the view cap.
      if ((kind === "ad_view" || kind === "ad_click") && key) {
        const col = kind === "ad_view" ? "views" : "clicks";
        this.sql.exec(
          `INSERT INTO ad_totals (ad_id, ${col}) VALUES (?, ?)
           ON CONFLICT(ad_id) DO UPDATE SET ${col} = ad_totals.${col} + excluded.${col}`,
          key, n,
        );
      }
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

    // Real account (user) totals, separate from anonymous device telemetry.
    const acc = this.sql.exec(
      "SELECT COUNT(*) AS total, SUM(CASE WHEN pro_until > ? THEN 1 ELSE 0 END) AS pro FROM accounts WHERE id != 'admin-user'",
      now,
    ).toArray()[0] || {};
    const adTotalsSum = this.sql.exec("SELECT COALESCE(SUM(views),0) AS v FROM ad_totals").toArray()[0] || {};

    return {
      generatedAt: now,
      days,
      online: this.onlineCount(),
      adWatchersNow: this.adWatchersNow(now),
      totalAccounts: Number(acc.total) || 0,
      proAccounts: Number(acc.pro) || 0,
      adViewsAllTime: Number(adTotalsSum.v) || 0,
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
