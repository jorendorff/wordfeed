// Renders the app's reader page for every source in headless Chromium and
// saves phone-sized screenshots, light and dark.
//
//   node tools/snap.mjs --live   [--record DIR] [--out DIR] [--only ID,...]
//   node tools/snap.mjs --replay DIR             [--out DIR] [--only ID,...]
//
// --live fetches from the real sites (and, with --record, saves every
// response so later runs can --replay them offline). URLs listed in
// tools/probe-urls.txt are also fetched and recorded, to help with writing
// new extractors.

import { chromium } from "playwright";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const ROOT = path.resolve(import.meta.dirname, "..");
const ASSETS = path.join(ROOT, "app/src/main/assets");
const UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36";

function parseArgs(argv) {
  const args = { out: "snap-out" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--live") args.live = true;
    else if (a === "--replay") args.replay = argv[++i];
    else if (a === "--record") args.record = argv[++i];
    else if (a === "--out") args.out = argv[++i];
    else if (a === "--only") args.only = argv[++i].split(",");
    else throw new Error("unknown argument " + a);
  }
  if (!args.live && !args.replay) throw new Error("need --live or --replay DIR");
  return args;
}

const args = parseArgs(process.argv.slice(2));
const index = {};
const cache = new Map();

function charsetOf(contentType) {
  const m = /charset=["']?([\w-]+)/i.exec(contentType || "");
  return m ? m[1].toLowerCase() : "utf-8";
}

async function liveFetch(url) {
  try {
    const r = await fetch(url, {
      headers: { "User-Agent": UA, "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
      redirect: "follow",
      signal: AbortSignal.timeout(30000),
    });
    const bytes = new Uint8Array(await r.arrayBuffer());
    let text;
    try {
      text = new TextDecoder(charsetOf(r.headers.get("content-type"))).decode(bytes);
    } catch {
      text = new TextDecoder("utf-8").decode(bytes);
    }
    return { ok: r.ok, status: r.status, finalUrl: r.url, contentType: r.headers.get("content-type"), text };
  } catch (e) {
    return { ok: false, status: 0, error: String(e), text: "" };
  }
}

async function record(url, res) {
  if (!args.record) return;
  const file = crypto.createHash("sha1").update(url).digest("hex").slice(0, 16) + ".txt";
  await fs.mkdir(path.join(args.record, "files"), { recursive: true });
  await fs.writeFile(path.join(args.record, "files", file), res.text);
  index[url] = {
    file, ok: res.ok, status: res.status, finalUrl: res.finalUrl,
    contentType: res.contentType, error: res.error, bytes: res.text.length,
  };
}

let replayIndex = null;
async function replayFetch(url) {
  if (!replayIndex) {
    replayIndex = JSON.parse(await fs.readFile(path.join(args.replay, "index.json"), "utf8"));
  }
  const e = replayIndex[url];
  if (!e) return { ok: false, error: "not recorded: " + url, text: "" };
  const text = await fs.readFile(path.join(args.replay, "files", e.file), "utf8");
  return { ok: e.ok, status: e.status, error: e.error, text };
}

async function getUrl(url) {
  if (!cache.has(url)) {
    cache.set(url, (async () => {
      const res = args.live ? await liveFetch(url) : await replayFetch(url);
      if (args.live) await record(url, res);
      return res;
    })());
  }
  const res = await cache.get(url);
  return { ok: res.ok, text: res.ok ? res.text : (res.error || `HTTP ${res.status}`) };
}

const sources = JSON.parse(await fs.readFile(path.join(ASSETS, "sources.json"), "utf8"))
  .filter((s) => !args.only || args.only.includes(s.id));

await fs.mkdir(args.out, { recursive: true });
const browser = await chromium.launch();
const report = [];

for (const source of sources) {
  for (const dark of [false, true]) {
    const page = await browser.newPage({
      viewport: { width: 412, height: 860 },
      deviceScaleFactor: 2,
      userAgent: UA,
      colorScheme: dark ? "dark" : "light",
    });
    const logs = [];
    page.on("console", (m) => logs.push(`${m.type()}: ${m.text()}`));
    page.on("pageerror", (e) => logs.push(`pageerror: ${e.message}`));
    await page.exposeFunction("__harnessFetch", getUrl);
    await page.addInitScript((cfg) => {
      const bodies = {};
      window.WordfeedNative = {
        config: () => cfg,
        fetch: (id, url) => {
          window.__harnessFetch(url).then((r) => {
            bodies[id] = r.text;
            window.__wf.ready(id, r.ok);
          });
        },
        take: (id) => {
          const b = bodies[id];
          delete bodies[id];
          return b == null ? "" : b;
        },
        rendered: (key) => { window.__renderedKey = key; },
      };
    }, JSON.stringify({ source, dark }));

    await page.goto(pathToFileURL(path.join(ASSETS, "reader.html")).href);
    let key;
    try {
      await page.waitForFunction(() => window.__renderedKey !== undefined, null, { timeout: 90000 });
      key = await page.evaluate(() => window.__renderedKey);
    } catch {
      logs.push("timed out waiting for render");
    }
    await page.waitForLoadState("networkidle", { timeout: 15000 }).catch(() => {});
    const shot = `${source.id}-${dark ? "dark" : "light"}.png`;
    await page.screenshot({ path: path.join(args.out, shot), fullPage: true });
    if (!dark) {
      await fs.writeFile(path.join(args.out, `${source.id}.html`), await page.content());
    }
    report.push({ source: source.id, dark, key, logs });
    console.log(`${shot}: key=${JSON.stringify(key)}${logs.length ? "\n  " + logs.join("\n  ") : ""}`);
    await page.close();
  }
}
await browser.close();

if (args.live) {
  const probeFile = path.join(ROOT, "tools/probe-urls.txt");
  const probes = (await fs.readFile(probeFile, "utf8")).split("\n")
    .map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
  for (const url of probes) {
    const r = await getUrl(url);
    console.log(`probe ${url}: ${r.ok ? "ok " + r.text.length + " chars" : r.text}`);
  }
}

if (args.record) {
  await fs.writeFile(path.join(args.record, "index.json"), JSON.stringify(index, null, 2));
}
await fs.writeFile(path.join(args.out, "report.json"), JSON.stringify(report, null, 2));
