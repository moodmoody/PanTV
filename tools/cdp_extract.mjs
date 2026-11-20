// 在真实页面里试提取：比赛名、时间、播放链接
const list = await fetch("http://127.0.0.1:9222/json").then((r) => r.json());
const page = list.find((x) => x.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pend = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pend.has(m.id)) { const q = pend.get(m.id); pend.delete(m.id); q(m.result); }
};
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const evalJs = async (expr) => {
  const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
  return r?.result?.value;
};

const EXTRACT = `
(function(){
  var links = document.querySelectorAll('a[href*="/play/"]');
  var rows = [], seen = {};
  for (var i = 0; i < links.length; i++) {
    var a = links[i];
    var href = a.getAttribute('href') || '';
    if (seen[href]) continue;
    seen[href] = 1;
    // 找"最小的、含时间的那层"＝这一场的行
    var box = a.parentElement, row = "";
    for (var d = 0; d < 6 && box; d++) {
      var t = (box.innerText || '').replace(/\s+/g, ' ').trim();
      if (/\d{1,2}-\d{1,2}\s*\d{1,2}:\d{2}/.test(t) && t.length < 70) { row = t; break; }
      box = box.parentElement;
    }
    var clean = row.replace(/直播[①②③④⑤⑥⑦⑧⑨0-9]*/g, ' ').replace(/\s+/g, ' ').trim();    rows.push({ href: href, text: clean.slice(0, 80) });
  }
  return JSON.stringify({ total: rows.length, sample: rows.slice(0, 8) });
})()
`;

console.log("提取结果:", await evalJs(EXTRACT));
ws.close();
