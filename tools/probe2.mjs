// 探测：容忍加载超时，页面拿到多少算多少
import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: "new",
  args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--lang=zh-CN", "--mute-audio"],
  defaultViewport: { width: 1440, height: 1000 },
});
const page = await browser.newPage();
await page.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36");

const media = new Set(), apis = new Set();
page.on("response", (res) => {
  const u = res.url();
  if (/\.(m3u8|flv|mp4)(\?|$)/i.test(u)) media.add(u);
  else if (/\/api\/|ajax|\.json(\?|$)/i.test(u)) apis.add(u);
});
page.on("request", (req) => {
  const u = req.url();
  if (/\.(m3u8|flv|mp4)(\?|$)/i.test(u)) media.add(u);
});

try {
  await page.goto("https://www.jrs21.com/", { waitUntil: "domcontentloaded", timeout: 20000 });
} catch (e) {
  console.log("（加载未完成，继续取当前 DOM）");
}
await new Promise((r) => setTimeout(r, 8000));

console.log("标题:", await page.title());
const info = await page.evaluate(() => {
  const txt = (document.body?.innerText || "").slice(0, 300);
  const links = [...document.querySelectorAll("a[href]")].slice(0, 400)
    .map((a) => ({ t: (a.textContent || "").trim().slice(0, 26), h: a.getAttribute("href") }))
    .filter((x) => x.h && !/^javascript/.test(x.h));
  return { bodyHead: txt.replace(/\s+/g, " "), linkCount: links.length, sample: links.slice(0, 25) };
});
console.log("正文开头:", info.bodyHead);
console.log("链接数:", info.linkCount);
console.log("样本:", JSON.stringify(info.sample, null, 0).slice(0, 800));
console.log("\n接口:", [...apis].slice(0, 8).join("\n  ") || "(无)");
console.log("媒体:", [...media].slice(0, 5).join("\n  ") || "(无)");

await browser.close();
