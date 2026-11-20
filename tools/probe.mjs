// 探测：用无头 Chrome 抓 jrs21 的接口与播放地址
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const PORT = 9551;
const ch = spawn(CHROME, [
  "--headless=new", "--disable-gpu", "--remote-debugging-port=" + PORT,
  "--user-data-dir=C:\\Users\\weife\\AppData\\Local\\Temp\\jrs_probe",
  "--window-size=1440,1000", "about:blank",
], { stdio: "ignore" });

let target = null;
for (let i = 0; i < 60; i++) {
  await sleep(250);
  try {
    const list = await fetch(`http://127.0.0.1:${PORT}/json/list`).then((r) => r.json());
    target = list.find((x) => x.type === "page");
    if (target) break;
  } catch {}
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pending = new Map();
const hits = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); q.resolve(m.result); return; }
  if (m.method === "Network.requestWillBeSent") {
    const u = m.params.request.url;
    if (/\.(m3u8|flv|mp4)|api|ajax|json/i.test(u) && !/\.(css|js|png|jpg|webp|woff)/i.test(u)) {
      hits.push({ kind: "req", method: m.params.request.method, url: u, post: (m.params.request.postData || "").slice(0, 200) });
    }
  }
};
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, { resolve: res }); ws.send(JSON.stringify({ id: i, method, params })); });

await send("Page.enable");
await send("Network.enable");
await send("Runtime.enable");
await send("Page.navigate", { url: "https://www.jrs21.com/" });
await sleep(9000);

const title = await send("Runtime.evaluate", { expression: "document.title", returnByValue: true });
console.log("标题:", title.result.value);

// 首页渲染出的比赛/链接
const links = await send("Runtime.evaluate", {
  expression: `JSON.stringify([...document.querySelectorAll('a')]
    .map(a => ({t:(a.textContent||'').trim().slice(0,24), h:a.getAttribute('href')}))
    .filter(x => x.h && !/^(javascript|#)/.test(x.h)).slice(0, 20))`,
  returnByValue: true,
});
console.log("链接样本:", links.result.value.slice(0, 900));

console.log("\n=== 捕获请求 ===");
const seen = new Set();
for (const h of hits) {
  const key = h.method + " " + h.url;
  if (seen.has(key)) continue;
  seen.add(key);
  console.log(` ${h.method} ${h.url.slice(0, 130)}${h.post ? "\n      body: " + h.post : ""}`);
  if (seen.size > 25) break;
}

ws.close();
ch.kill();
