// FAIROS tunnel — a VLESS-over-WebSocket proxy that runs on a Cloudflare Worker.
// Clients connect with VLESS + WS + TLS on port 443; the Worker opens the outbound
// TCP connection from Cloudflare's edge, so filtered destinations become reachable.
// UDP is only handled for DNS (port 53), forwarded over DoH.
//
// NOTE: cloudflare:sockets is loaded lazily (dynamic import) so that a failure to
// load it surfaces as a readable error on /health instead of a raw 1101 page.

let USER_ID = "22be472e-d0cd-4c45-ae0a-6df6b12fe711";
// Optional fallback relay for destinations Cloudflare can't reach directly (set via PROXYIP var).
let PROXY_IP = "";

let _connect = null;
async function getConnect() {
  if (_connect) return _connect;
  const mod = await import("cloudflare:sockets");
  _connect = mod.connect;
  return _connect;
}

export default {
  async fetch(request, env) {
    try {
      USER_ID = (env.UUID || USER_ID).trim();
      PROXY_IP = (env.PROXYIP || PROXY_IP).trim();
      const upgrade = request.headers.get("Upgrade");
      if (upgrade !== "websocket") {
        const url = new URL(request.url);
        if (url.pathname === "/") return new Response("ok", { status: 200 });
        if (url.pathname === "/health") {
          // Runtime self-test: can this Worker open an outbound TCP socket?
          try {
            const connect = await getConnect();
            const socket = connect({ hostname: "1.1.1.1", port: 443 });
            if (socket.opened) await socket.opened;
            try { await socket.close(); } catch (e) {}
            return new Response("sockets ok", { status: 200 });
          } catch (e) {
            return new Response("health err: " + (e && e.message ? e.message : e), { status: 200 });
          }
        }
        if (url.pathname === `/sub/${USER_ID}`) {
          const host = request.headers.get("Host");
          return new Response(buildVlessLink(host), {
            status: 200,
            headers: { "Content-Type": "text/plain; charset=utf-8" },
          });
        }
        return new Response("Not found", { status: 404 });
      }
      return await handleWs(request);
    } catch (err) {
      return new Response("err: " + (err && err.message ? err.message : err), { status: 500 });
    }
  },
};

function buildVlessLink(host) {
  const q = `encryption=none&security=tls&sni=${host}&fp=chrome&type=ws&host=${host}&path=${encodeURIComponent("/?ed=2560")}`;
  return `vless://${USER_ID}@${host}:443?${q}#FAIROS-CF`;
}

async function handleWs(request) {
  const pair = new WebSocketPair();
  const [client, ws] = Object.values(pair);
  ws.accept();

  let remote = { value: null };
  let isDns = false;
  let udpWrite = null;
  const early = request.headers.get("sec-websocket-protocol") || "";
  const readable = wsReadable(ws, early);

  readable.pipeTo(new WritableStream({
    async write(chunk) {
      if (isDns && udpWrite) return udpWrite(chunk);
      if (remote.value) {
        const w = remote.value.writable.getWriter();
        await w.write(chunk);
        w.releaseLock();
        return;
      }
      const head = parseVlessHeader(chunk, USER_ID);
      if (head.hasError) throw new Error(head.message);
      const resHeader = new Uint8Array([head.version[0], 0]);
      const rawData = chunk.slice(head.rawIndex);
      if (head.isUDP) {
        if (head.port !== 53) throw new Error("UDP only supported for DNS");
        isDns = true;
        udpWrite = await setupDnsOverDoh(ws, resHeader);
        udpWrite(rawData);
        return;
      }
      await outboundTcp(remote, head.address, head.port, rawData, ws, resHeader);
    },
    close() {},
    abort() {},
  })).catch(() => safeClose(ws));

  return new Response(null, { status: 101, webSocket: client });
}

async function outboundTcp(remote, address, port, rawData, ws, resHeader) {
  async function open(addr) {
    const connect = await getConnect();
    const socket = connect({ hostname: addr, port });
    remote.value = socket;
    const w = socket.writable.getWriter();
    await w.write(rawData);
    w.releaseLock();
    return socket;
  }
  async function retry() {
    const socket = await open(PROXY_IP || address);
    socket.closed.catch(() => {}).finally(() => safeClose(ws));
    pipeRemoteToWs(socket, ws, resHeader, null);
  }
  const socket = await open(address);
  // If the first connection yields no data, fall back to the relay (if configured).
  pipeRemoteToWs(socket, ws, resHeader, retry);
}

async function pipeRemoteToWs(socket, ws, resHeader, retry) {
  let hasData = false;
  let headerSent = false;
  await socket.readable.pipeTo(new WritableStream({
    write(chunk) {
      hasData = true;
      if (ws.readyState !== 1) return;
      if (!headerSent) {
        const merged = new Uint8Array(resHeader.byteLength + chunk.byteLength);
        merged.set(resHeader, 0);
        merged.set(new Uint8Array(chunk), resHeader.byteLength);
        ws.send(merged.buffer);
        headerSent = true;
      } else {
        ws.send(chunk);
      }
    },
    close() {},
    abort() {},
  })).catch(() => safeClose(ws));
  if (!hasData && retry) return retry();
}

function parseVlessHeader(buffer, userId) {
  if (buffer.byteLength < 24) return { hasError: true, message: "header too short" };
  const view = new DataView(buffer);
  const version = new Uint8Array(buffer.slice(0, 1));
  const id = new Uint8Array(buffer.slice(1, 17));
  if (stringifyUuid(id) !== userId) return { hasError: true, message: "invalid user" };
  const optLen = view.getUint8(17);
  const command = view.getUint8(18 + optLen);
  let isUDP = false;
  if (command === 1) isUDP = false;
  else if (command === 2) isUDP = true;
  else return { hasError: true, message: "unsupported command " + command };
  let pos = 18 + optLen + 1;
  const port = view.getUint16(pos);
  pos += 2;
  const addrType = view.getUint8(pos);
  pos += 1;
  let address = "";
  if (addrType === 1) {
    address = new Uint8Array(buffer.slice(pos, pos + 4)).join(".");
    pos += 4;
  } else if (addrType === 2) {
    const len = view.getUint8(pos);
    pos += 1;
    address = new TextDecoder().decode(buffer.slice(pos, pos + len));
    pos += len;
  } else if (addrType === 3) {
    const parts = [];
    for (let i = 0; i < 8; i++) { parts.push(view.getUint16(pos).toString(16)); pos += 2; }
    address = parts.join(":");
  } else {
    return { hasError: true, message: "bad address type " + addrType };
  }
  return { hasError: false, version, isUDP, port, address, rawIndex: pos };
}

function wsReadable(ws, earlyHeader) {
  let cancelled = false;
  return new ReadableStream({
    start(controller) {
      ws.addEventListener("message", (e) => { if (!cancelled) controller.enqueue(e.data); });
      ws.addEventListener("close", () => { try { controller.close(); } catch (x) {} });
      ws.addEventListener("error", () => { try { controller.error(); } catch (x) {} });
      const { earlyData, error } = base64ToBuf(earlyHeader);
      if (error) controller.error(error);
      else if (earlyData) controller.enqueue(earlyData);
    },
    cancel() { cancelled = true; safeClose(ws); },
  });
}

async function setupDnsOverDoh(ws, resHeader) {
  let headerSent = false;
  const { readable, writable } = new TransformStream({
    transform(chunk, controller) {
      // VLESS UDP frames: 2-byte length + payload
      for (let i = 0; i < chunk.byteLength;) {
        const len = new DataView(chunk.buffer, chunk.byteOffset + i, 2).getUint16(0);
        controller.enqueue(new Uint8Array(chunk.slice(i + 2, i + 2 + len)));
        i += 2 + len;
      }
    },
  });
  readable.pipeTo(new WritableStream({
    async write(query) {
      const r = await fetch("https://1.1.1.1/dns-query", {
        method: "POST",
        headers: { "Content-Type": "application/dns-message" },
        body: query,
      });
      const ans = new Uint8Array(await r.arrayBuffer());
      const sizeBuf = new Uint8Array([(ans.byteLength >> 8) & 0xff, ans.byteLength & 0xff]);
      if (ws.readyState !== 1) return;
      if (!headerSent) {
        ws.send(new Uint8Array([...resHeader, ...sizeBuf, ...ans]).buffer);
        headerSent = true;
      } else {
        ws.send(new Uint8Array([...sizeBuf, ...ans]).buffer);
      }
    },
  })).catch(() => {});
  const writer = writable.getWriter();
  return (chunk) => writer.write(chunk);
}

function base64ToBuf(b64) {
  if (!b64) return { earlyData: null, error: null };
  try {
    const normal = b64.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(normal);
    const buf = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return { earlyData: buf.buffer, error: null };
  } catch (e) {
    return { earlyData: null, error: e };
  }
}

const HEX = [];
for (let i = 0; i < 256; i++) HEX.push((i + 256).toString(16).slice(1));
function stringifyUuid(arr) {
  return (
    HEX[arr[0]] + HEX[arr[1]] + HEX[arr[2]] + HEX[arr[3]] + "-" +
    HEX[arr[4]] + HEX[arr[5]] + "-" + HEX[arr[6]] + HEX[arr[7]] + "-" +
    HEX[arr[8]] + HEX[arr[9]] + "-" +
    HEX[arr[10]] + HEX[arr[11]] + HEX[arr[12]] + HEX[arr[13]] + HEX[arr[14]] + HEX[arr[15]]
  ).toLowerCase();
}

function safeClose(ws) {
  try { if (ws.readyState === 1 || ws.readyState === 2) ws.close(); } catch (e) {}
}
