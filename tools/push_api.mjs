// 通过 GitHub Git Data API 提交（绕过被墙的 github.com HTTPS）
import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";

const REPO = "moodmoody/PanTV";
const BRANCH = "main";
const args = process.argv.slice(2);
const MSG = (args.find((a) => a.startsWith("--msg=")) || "").replace("--msg=", "") || "更新";
const FILE_ARG = (args.find((a) => a.startsWith("--files=")) || "").replace("--files=", "");
const ROOT = "C:/Users/weife/Documents/deepseek-harness/default-workspace/jrs-tv";

const cred = execSync(
  'git credential-manager get',
  { input: "protocol=https\nhost=github.com\n\n", encoding: "utf8", env: { ...process.env, GIT_TERMINAL_PROMPT: "0" } }
);
const token = (cred.match(/password=(.+)/) || [])[1]?.trim();
if (!token) { console.error("拿不到令牌"); process.exit(1); }

const API = "https://api.github.com";
const H = {
  Authorization: `Bearer ${token}`,
  Accept: "application/vnd.github+json",
  "User-Agent": "PanTV-push",
  "Content-Type": "application/json",
};

async function api(path, opts = {}) {
  const res = await fetch(API + path, { ...opts, headers: { ...H, ...(opts.headers || {}) } });
  const text = await res.text();
  let body = null;
  try { body = JSON.parse(text); } catch { body = text; }
  if (!res.ok) throw new Error(`${opts.method || "GET"} ${path} → ${res.status} ${JSON.stringify(body).slice(0, 200)}`);
  return body;
}

// 1) 当前分支指向
const ref = await api(`/repos/${REPO}/git/ref/heads/${BRANCH}`);
const parentSha = ref.object.sha;
const parentCommit = await api(`/repos/${REPO}/git/commits/${parentSha}`);
console.log("当前 HEAD:", parentSha.slice(0, 8), "|", parentCommit.message.split("\n")[0]);

// 2) 本次要提交的文件
const files = FILE_ARG ? FILE_ARG.split(",").map((s) => s.trim()).filter(Boolean)
  : [".gitignore", "LICENSE", "README.md", "docs/lines.png", "docs/list.png"];
const entries = [];
for (const f of files) {
  const buf = readFileSync(`${ROOT}/${f}`);
  const isText = /\.(md|gitignore|txt|kt|kts|xml|json)$/.test(f) || f === ".gitignore";
  const blob = await api(`/repos/${REPO}/git/blobs`, {
    method: "POST",
    body: JSON.stringify({ content: buf.toString("base64"), encoding: "base64" }),
  });
  entries.push({
    path: f,
    mode: "100644",
    type: "blob",
    sha: blob.sha,
  });
  console.log(`  上传 ${f}  ${buf.length} 字节 → ${blob.sha.slice(0, 8)}${isText ? "" : " (二进制)"}`);
}

// 3) 建树（基于父提交的树）
const tree = await api(`/repos/${REPO}/git/trees`, {
  method: "POST",
  body: JSON.stringify({ base_tree: parentCommit.tree.sha, tree: entries }),
});
console.log("新树:", tree.sha.slice(0, 8), `(${entries.length} 个条目)`);

// 4) 建提交
const commit = await api(`/repos/${REPO}/git/commits`, {
  method: "POST",
  body: JSON.stringify({ message: MSG, tree: tree.sha, parents: [parentSha] }),
});
console.log("新提交:", commit.sha.slice(0, 8));

// 5) 移动分支
const updated = await api(`/repos/${REPO}/git/refs/heads/${BRANCH}`, {
  method: "PATCH",
  body: JSON.stringify({ sha: commit.sha, force: false }),
});
console.log("分支已更新:", updated.object.sha.slice(0, 8));
console.log("\n完成：https://github.com/" + REPO);