// 在电视上打开一场正在播的比赛，看播放页怎么列线路
import { execSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const ADB = "C:\\Users\\weife\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";
const DEV = "192.168.5.232:5555";
const sh = (c) => execSync(`"${ADB}" -s ${DEV} shell ${c}`, { encoding: "utf8" });

// 拿 WebView 调试端口
const unix = execSync(`"${ADB}" -s ${DEV} shell "cat /proc/net/unix | grep webview_devtools"`, { encoding: "utf8" });
const pid = (unix.match(/remote_(\d+)/) || [])[1];
if (!pid) { console.log("没有 WebView 调试目标，先启动 App"); process.exit(1); }
execSync(`"${ADB}" -s ${DEV} forward --remove-all`);
execSync(`"${ADB}" -s ${DEV} forward tcp:9222 localabstract:webview_devtools_remote_${pid}`);
console.log("WebView pid:", pid);

const list = await fetch("http://127.0.0.1:9222/json").then((r) => r.json());
const page = list.find((x) => x.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pend = new Map();
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { const q = pend.get(m.id); pend.delete(m.id); q(m.result); } };
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true }))?.result?.value;

// 1) 回到列表页
await send("Page.navigate", { url: "https://m.jrs21.com/" });
await sleep(12000);

// 2) 找一场"进行中"的（文本里有分钟数）
const pick = await ev(`(function(){
  var blocks = document.querySelectorAll('[data-lid]');
  for (var i=0;i<blocks.length;i++){
    var b=blocks[i]; if(b.offsetParent===null) continue;
    var t=(b.innerText||'').replace(/\\s+/g,' ');
    if(/\\d+'/.test(t)){
      var a=b.querySelector('a[href*="/play/"]');
      if(a) return JSON.stringify({lid:b.getAttribute('data-lid'), text:t.slice(0,60), href:a.getAttribute('href')});
    }
  }
  return 'none';
})()`);
console.log("选中比赛:", pick);
if (!pick || pick === "none") { ws.close(); process.exit(0); }

const info = JSON.parse(pick);

// 3) 打开发播放页，抓结构
await send("Page.navigate", { url: info.href });
await sleep(10000);

const dump = await ev(`(function(){
  var out = { url: location.href, title: document.title, iframes: [], links: [], buttons: [] };
  document.querySelectorAll('iframe').forEach(function(f){ out.iframes.push(f.src) });
  document.querySelectorAll('a').forEach(function(a){
    var t=(a.textContent||'').replace(/\\s+/g,' ').trim();
    if(t && t.length<14) out.links.push({ t:t, h:(a.getAttribute('href')||'').slice(0,90) });
  });
  document.querySelectorAll('button,[onclick]').forEach(function(b){
    var t=(b.textContent||'').replace(/\\s+/g,' ').trim();
    if(t && t.length<14) out.buttons.push(t);
  });
  out.links = out.links.slice(0,15);
  out.buttons = out.buttons.slice(0,10);
  return JSON.stringify(out);
})()`);
console.log("播放页结构:", dump);
ws.close();