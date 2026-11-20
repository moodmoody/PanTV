// 用 puppeteer-core 驱动本机 Chrome 渲染页面，抓比赛列表与播放地址
import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: "new",
  args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--lang=zh-CN"],
  defaultViewport: { width: 1440, height: 1000 },
});

const page = await browser.newPage();
const media = [];
const apis = [];

page.on("response", (res) => {
  const u = res.url();
  if (/\.(m3u8|flv|mp4)(\?|$)/i.test(u)) media.push(u);
  else if (/api|ajax|\.json|live|match/i.test(u) && !/\.(css|js|png|jpg|webp|woff2?)/i.test(u)) apis.push(u);
});

console.log("打开首页…");
await page.goto("https://www.jrs21.com/", { waitUntil: "commit", timeout: 25000 });
await new Promise((r) => setTimeout(r, 9000));

const title = await page.title();
console.log("标题:", title);

const info = await page.evaluate(() => {
  const links = [...document.querySelectorAll("a[href]")]
    .map((a) => ({ text: (a.textContent || "").trim().slice(0, 30), href: a.getAttribute("href") }))
    .filter((x) => x.href && !/^javascript/.test(x.href));
  const rows = [...document.querySelectorAll("li,tr,.item,.match")].length;
  return { linkCount: links.length, sample: links.slice(0, 15), rows };
});
console.log("链接数:", info.linkCount, "行元素:", info.rows);
console.log("样本:", JSON.stringify(info.sample, null, 1).slice(0, 700));

console.log("\n接口请求:", [...new Set(apis)].slice(0, 10).join("\n  ") || "(无)");
console.log("媒体地址:", [...new Set(media)].slice(0, 5).join("\n  ") || "(无)");

await browser.close();
