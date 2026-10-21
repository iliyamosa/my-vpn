// FAIROSVPN backend: live settings, instant updates and anonymous usage stats.
//
// Privacy: no IP addresses, no visited sites, no traffic data are stored.
// A device is only a random id chosen by the app, plus phone model / Android / app version.

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
    if (path === "/") return json({ ok: true, service: "fairosvpn" });

    const routes = ["/ws", "/config", "/events", "/admin/config", "/admin/summary", "/admin/users", "/admin/ping"];
    if (!routes.includes(path)) return json({ error: "not found" }, 404);

    if (path.startsWith("/admin/") && !(await isAdmin(request, env))) {
      return json({ error: "unauthorized" }, 401);
    }
    const stub = env.HUB.get(env.HUB.idFromName("main"));
    return stub.fetch(request);
  },
};

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
  }

  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");
    try {
      switch (path) {
        case "/ws":
          return this.handleWebSocket(request);
        case "/config":
          return json(await this.getConfig());
        case "/events":
          if (request.method !== "POST") return json({ error: "method" }, 405);
          return this.handleEvents(await request.json());
        case "/admin/ping":
          return json({ ok: true, online: this.onlineCount() });
        case "/admin/config":
          if (request.method === "PUT" || request.method === "POST") return this.putConfig(await request.json());
          return json(await this.getConfig());
        case "/admin/summary":
          return json(this.summary(Number(url.searchParams.get("days") || 14)));
        case "/admin/users":
          return json(
            this.users(
              url.searchParams.get("tier") || "all",
              Number(url.searchParams.get("offset") || 0),
              Number(url.searchParams.get("limit") || 50),
              url.searchParams.get("q") || "",
            ),
          );
        default:
          return json({ error: "not found" }, 404);
      }
    } catch (e) {
      return json({ error: String((e && e.message) || e) }, 400);
    }
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
