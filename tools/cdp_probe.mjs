// 通过 CDP 检查电视上 WebView 里的页面，找出播放按钮的真实行为
const list = await fetch("http://127.0.0.1:9222/json").then((r) => r.json());
const page = list.find((x) => x.type === "page");
if (!page) { console.log("没有找到页面目标"); process.exit(1); }

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

console.log("当前地址:", await evalJs("location.href"));

const cands = await evalJs(`
JSON.stringify([...document.querySelectorAll('a,button,div[onclick],span')]
  .filter(e => /直播|播放/.test(e.textContent || '') && (e.textContent||'').length < 12)
  .slice(0, 8)
  .map(e => ({
    tag: e.tagName,
    text: (e.textContent || '').trim(),
    href: e.getAttribute('href'),
    onclick: e.getAttribute('onclick'),
    cls: (e.className || '').toString().slice(0, 40)
  })))
`);
console.log("候选按钮:", cands);

// 试着点第一个
const clicked = await evalJs(`
(function(){
  var els = [...document.querySelectorAll('a,button,div[onclick],span')]
    .filter(e => /直播|播放/.test(e.textContent || '') && (e.textContent||'').length < 12);
  if (!els.length) return 'no-target';
  var e = els[0];
  e.scrollIntoView({block:'center'});
  e.click();
  return 'clicked ' + e.tagName + ' ' + (e.textContent||'').trim();
})()
`);
console.log("点击结果:", clicked);

await new Promise((r) => setTimeout(r, 5000));
console.log("点击后地址:", await evalJs("location.href"));
console.log("iframe 数:", await evalJs("document.querySelectorAll('iframe').length"));
console.log("video 数:", await evalJs("document.querySelectorAll('video').length"));

ws.close();
