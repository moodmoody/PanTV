// 打开播放页，抓真实媒体请求（m3u8 / ts / ws）
const list = await fetch("http://127.0.0.1:9222/json").then((r) => r.json());
const page = list.find((x) => x.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });

let id = 0; const pend = new Map();
const media = new Set(), xhr = new Set(), wsurls = new Set();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const q = pend.get(m.id); pend.delete(m.id); q(m.result); return; }
  if (m.method === "Network.requestWillBeSent") {
    const u = m.params.request.url;
    if (/\.(m3u8|ts|flv|mp4|m4s)(\?|$)/i.test(u)) media.add(u);
    else if (/\.(php|json)(\?|$)/i.test(u)) xhr.add(u);
  }
  if (m.method === "Network.webSocketCreated") wsurls.add(m.params.url);
};
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

await send("Network.enable");
await send("Page.enable");

console.log("跳到播放页…");
await send("Page.navigate", { url: "http://play.sportsteam53.com/play/steam827011.html" });
await new Promise((r) => setTimeout(r, 14000));

const info = await send("Runtime.evaluate", {
  expression: `JSON.stringify({
    url: location.href,
    title: document.title,
    videos: [...document.querySelectorAll('video')].map(v => ({src: v.src, cur: v.currentSrc})),
    iframes: [...document.querySelectorAll('iframe')].map(f => f.src),
    bodyHead: (document.body?.innerText || '').slice(0, 120).replace(/\\s+/g,' ')
  })`, returnByValue: true,
});
console.log("页面状态:", info.result.value);

console.log("\n媒体请求：");
[...media].slice(0, 10).forEach((u) => console.log("  " + u.slice(0, 150)));
if (!media.size) console.log("  (没有 m3u8/ts 请求)");

console.log("\nWebSocket：");
[...wsurls].slice(0, 5).forEach((u) => console.log("  " + u.slice(0, 120)));

console.log("\n接口：");
[...xhr].slice(0, 8).forEach((u) => console.log("  " + u.slice(0, 120)));

ws.close();
