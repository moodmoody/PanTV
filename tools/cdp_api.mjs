// 抓站点加载过程中的全部数据请求，找比赛列表接口
const list = await fetch("http://127.0.0.1:9222/json").then((r) => r.json());
const page = list.find((x) => x.type === "page");
if (!page) { console.log("没有页面目标"); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pend = new Map();
const hits = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const q = pend.get(m.id); pend.delete(m.id); q(m.result); return; }
  if (m.method === "Network.requestWillBeSent") {
    const r = m.params.request;
    const u = r.url;
    if (/\.(css|js|png|jpg|jpeg|webp|gif|svg|ico|woff2?)(\?|$)/i.test(u)) return;
    hits.push({ m: r.method, u, type: m.params.type });
  }
};
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

await send("Network.enable");
await send("Page.enable");
await send("Page.navigate", { url: "https://m.jrs21.com/" });
await new Promise((r) => setTimeout(r, 12000));

console.log("数据类请求：");
const seen = new Set();
for (const h of hits) {
  const k = h.m + " " + h.u;
  if (seen.has(k)) continue;
  seen.add(k);
  console.log(`  [${h.type}] ${h.m} ${h.u.slice(0, 160)}`);
}

ws.close();
