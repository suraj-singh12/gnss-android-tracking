#!/usr/bin/env node
// Issue #88 POC captures: baseline vs enhanced in UNMODIFIED Command, the POC
// reference viewer (reference-only layers), the current Maps & Layers UI and
// the simulated Get a Map proposal. Command runs inside a macOS sandbox that
// denies every outbound connection except loopback; the browser also blocks
// and records any non-loopback request.
//
//   GNSS_PLAYWRIGHT_MODULE=~/gnss-poc-88-work/node/node_modules/playwright \
//   node capture.cjs --run ~/gnss-poc-88-work/runs/cold --out ~/gnss-poc-88-work/captures \
//        --command ~/gnss-poc-88-work/bin/gnss-command
"use strict";
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const net = require("net");

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const RUN = path.resolve(arg("run").replace(/^~/, os.homedir()));
const OUT = path.resolve(arg("out").replace(/^~/, os.homedir()));
const BIN = path.resolve(arg("command", "~/gnss-poc-88-work/bin/gnss-command").replace(/^~/, os.homedir()));
const ONLY = arg("only", "viewer,command,ui,design").split(",");
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
fs.mkdirSync(OUT, { recursive: true });
const runInfo = JSON.parse(fs.readFileSync(path.join(RUN, "run.json")));
const [LAT, LON] = runInfo.input.centre;
const VIEWS = [
  { id: "overview", lat: LAT, lon: LON, z: 11 },
  { id: "arinsal-detail", lat: 42.5722, lon: 1.4847, z: 14 },
  { id: "coma-pedrosa-detail", lat: 42.5917, lon: 1.4442, z: 14 },
  { id: "andorra-la-vella-detail", lat: 42.5078, lon: 1.5211, z: 15 },
];
const SANDBOX = '(version 1)(allow default)(deny network-outbound)(allow network-outbound (remote ip "localhost:*"))(allow network-outbound (remote unix-socket))';
const results = { run: RUN, captured_at: new Date().toISOString(), external_requests_blocked: [], viewer: [], command: [], ui: [], design: [] };

const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitHttp(url, ms = 30000) {
  const t = Date.now();
  while (Date.now() - t < ms) { try { const r = await fetch(url); if (r.status < 500) return; } catch (_) {} await sleep(200); }
  throw new Error("timeout " + url);
}
function guard(page) {
  page.route("**/*", (route) => {
    const u = new URL(route.request().url());
    if (["127.0.0.1", "localhost"].includes(u.hostname) || u.protocol === "file:" || u.protocol === "data:" || u.protocol === "blob:") return route.continue();
    results.external_requests_blocked.push(route.request().url());
    return route.abort();
  });
}

async function startCommand(db) {
  const port = await freePort();
  const ingest = await freePort();
  const env = { ...process.env };
  for (const k of ["HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"]) delete env[k];
  const proc = spawn("/usr/bin/sandbox-exec", ["-p", SANDBOX, BIN, "-db", db, "-dashboard-listen", `127.0.0.1:${port}`, "-ingest-listen", `127.0.0.1:${ingest}`],
    { env, stdio: ["ignore", fs.openSync(path.join(OUT, "command-capture.log"), "a"), fs.openSync(path.join(OUT, "command-capture.log"), "a")] });
  const base = `http://127.0.0.1:${port}`;
  await waitHttp(base + "/local/maps");
  return { proc, base };
}
async function importPkg(base, file) {
  const r = await fetch(base + "/local/map-package", { method: "POST", headers: { "Content-Type": "application/zip" }, body: fs.readFileSync(file) });
  const j = await r.json();
  if (r.status !== 200) throw new Error("import " + file + ": " + JSON.stringify(j));
  return j;
}

async function viewerCaptures(browser) {
  const port = await freePort();
  const srv = spawn("python3", [path.join(__dirname, "viewer", "serve.py"), "--run", RUN, "--port", String(port)], { stdio: "ignore" });
  await waitHttp(`http://127.0.0.1:${port}/meta.json`);
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  guard(page);
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.__poc && window.__poc.draws.length > 0, null, { timeout: 60000 });
  for (const v of VIEWS) for (const mode of ["topo", "terrain", "satellite", "hybrid"]) {
    await page.evaluate(([a, b, z, m]) => window.__pocView(a, b, z, m), [v.lat, v.lon, v.z, mode]);
    await page.waitForFunction(() => window.__poc.ready, null, { timeout: 30000 }).catch(() => {});
    await sleep(400);
    const ms = await page.evaluate(([a, b, z, m]) => { const t = performance.now(); window.__pocView(a, b, z, m); return performance.now() - t; }, [v.lat, v.lon, v.z, mode]);
    const file = `viewer-${v.id}-${mode}.png`;
    await page.screenshot({ path: path.join(OUT, file) });
    results.viewer.push({ view: v, mode, file, redraw_ms: Math.round(ms * 10) / 10, note: await page.textContent("#note") });
  }
  // Pan/zoom responsiveness: 20 synthetic pans at z14 in hybrid.
  await page.evaluate(([a, b]) => window.__pocView(a, b, 14, "hybrid"), [LAT, LON]);
  await sleep(1500);
  const pan = await page.evaluate(async () => {
    const times = [];
    const c = document.getElementById("map");
    for (let i = 0; i < 20; i++) {
      const t = performance.now();
      c.dispatchEvent(new KeyboardEvent("keydown", { key: i % 2 ? "ArrowLeft" : "ArrowRight", bubbles: true }));
      await new Promise((r) => requestAnimationFrame(() => r()));
      times.push(performance.now() - t);
    }
    times.sort((x, y) => x - y);
    return { frames: times.length, median_ms: times[10], max_ms: times[19] };
  });
  results.viewer_pan_hybrid_z14 = pan;
  await page.close();
  srv.kill();
}

async function commandCaptures(browser) {
  // Each map gets its own Command database and a fresh browser context, so the
  // baseline and enhanced captures follow identical steps with no carried-over state.
  const files = {
    baseline: fs.readdirSync(path.join(RUN, "command")).find((f) => f.endsWith("-baseline.gmap.zip")),
    enhanced: fs.readdirSync(path.join(RUN, "command")).find((f) => f.endsWith("-enhanced.gmap.zip")),
  };
  results.command_maps = {};
  for (const [kind, f] of Object.entries(files)) {
    if (!f) continue;
    const db = path.join(os.tmpdir(), `poc88-capture-${kind}-${Date.now()}.sqlite`);
    const { proc, base } = await startCommand(db);
    const id = (await importPkg(base, path.join(RUN, "command", f))).id;
    results.command_maps[kind] = id;
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await ctx.newPage();
    guard(page);
    await page.goto(base + "/");
    await page.waitForSelector("#saved-maps option[value]:not([value=''])", { state: "attached", timeout: 30000 });
    await page.evaluate((mid) => { const s = document.getElementById("saved-maps"); s.value = mid; s.dispatchEvent(new Event("change")); }, id);
    await page.waitForLoadState("networkidle");
    await sleep(1500);
    await page.click("#open-layers");
    for (const k of ["hillshade", "contours"]) {
      const el = await page.$(`#layer-${k}`);
      if (el && !(await el.isDisabled()) && !(await el.isChecked())) await el.click();
    }
    const layerState = await page.evaluate(() => ["hillshade", "contours"].map((k) => [k, document.getElementById("layer-" + k).checked]));
    await page.click("#apply-layers");
    for (const v of VIEWS) {
      const t0 = Date.now();
      await page.evaluate(([lat, lon, z]) => {
        const [cx, cy] = GeoMap.project(lon, lat);
        viewport = { cx, cy, scale: (256 * 2 ** z) / (2 * Math.PI * 6378137) }; // eslint-disable-line no-undef
        draw(); // eslint-disable-line no-undef
      }, [v.lat, v.lon, v.z]);
      await page.waitForLoadState("networkidle");
      await sleep(1500);
      const settle = Date.now() - t0;
      const file = `command-${kind}-${v.id}.png`;
      await page.screenshot({ path: path.join(OUT, file), clip: { x: 242, y: 106, width: 928, height: 794 } });
      const warn = await page.evaluate(() => [...document.querySelectorAll("#map-warning, #map-mode-label, .map-notice")].map((e) => e.textContent.trim()).filter(Boolean).join(" | "));
      results.command.push({ kind, view: v, file, settle_ms: settle, layers: layerState, notices: warn.slice(0, 400) });
    }
    await ctx.close();
    proc.kill();
    fs.rmSync(db, { force: true });
  }
}

async function uiCaptures(browser) {
  const db = path.join(os.tmpdir(), `poc88-ui-${Date.now()}.sqlite`);
  const { proc, base } = await startCommand(db);
  const f = fs.readdirSync(path.join(RUN, "command")).find((x) => x.endsWith("-enhanced.gmap.zip"));
  if (f) await importPkg(base, path.join(RUN, "command", f));
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  guard(page);
  await page.goto(base + "/");
  await sleep(1500);
  await page.click("#open-map").catch(async () => { await page.evaluate(() => document.getElementById("open-map").click()); });
  await sleep(600);
  for (const [tab, name] of [["tab-map-preparation", "get-a-map"], ["tab-map-import", "import"], ["tab-maps-library", "my-maps"]]) {
    const t = await page.$("#" + tab);
    if (!t) continue;
    await t.click();
    await sleep(400);
    const file = `current-ui-${name}.png`;
    await page.screenshot({ path: path.join(OUT, file) });
    results.ui.push({ state: name, file, viewport: "1280x800", source: "unmodified Command at POC parent b882d42" });
  }
  const display = await page.$("[id^=tab-][role=tab]:last-of-type");
  if (display) { await display.click(); await sleep(300); await page.screenshot({ path: path.join(OUT, "current-ui-display.png") }); results.ui.push({ state: "display", file: "current-ui-display.png" }); }
  await page.keyboard.press("Escape");
  await sleep(300);
  await page.click("#open-layers").catch(() => {});
  await sleep(300);
  await page.screenshot({ path: path.join(OUT, "current-ui-layers.png") });
  results.ui.push({ state: "layers-popup", file: "current-ui-layers.png" });
  await page.close();
  proc.kill();
  fs.rmSync(db, { force: true });
}

async function designCaptures(browser) {
  const file = "file://" + path.join(__dirname, "design", "get-a-map-prototype.html");
  for (const width of ["wide", "narrow"]) {
    const page = await browser.newPage({ viewport: width === "wide" ? { width: 1280, height: 860 } : { width: 430, height: 900 } });
    guard(page);
    await page.goto(file);
    if (width === "narrow") await page.click('[data-width="narrow"]');
    for (const st of ["area", "review", "progress", "ready", "limited", "partial", "failed", "offline", "storage"]) {
      await page.click(`[data-state="${st}"]`);
      await sleep(150);
      const overflow = await page.evaluate(() => {
        const d = document.querySelector("dialog");
        const bad = [...d.querySelectorAll("*")].filter((e) => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflowX === "visible" && e.clientWidth > 0).map((e) => e.className || e.tagName);
        return { dialogScrollsX: d.scrollWidth > d.clientWidth + 1, clipped: bad.slice(0, 5) };
      });
      const out = `design-${width}-${st}.png`;
      await page.screenshot({ path: path.join(OUT, out) });
      results.design.push({ width, state: st, file: out, overflow });
    }
    await page.close();
  }
}

(async () => {
  const browser = await chromium.launch(process.env.GNSS_CHROMIUM ? { executablePath: process.env.GNSS_CHROMIUM } : {});
  try {
    if (ONLY.includes("viewer")) await viewerCaptures(browser);
    if (ONLY.includes("command")) await commandCaptures(browser);
    if (ONLY.includes("ui")) await uiCaptures(browser);
    if (ONLY.includes("design")) await designCaptures(browser);
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(OUT, "captures.json"), JSON.stringify(results, null, 1));
  }
  console.log(JSON.stringify({ viewer: results.viewer.length, command: results.command.length, ui: results.ui.length, design: results.design.length,
    external_requests_blocked: results.external_requests_blocked.length, pan: results.viewer_pan_hybrid_z14 }, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
