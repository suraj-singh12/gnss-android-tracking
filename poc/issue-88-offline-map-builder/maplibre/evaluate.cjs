#!/usr/bin/env node
// Evaluate the offline MapLibre POC: restart the server inside a macOS sandbox that
// denies all outbound network except loopback, open it in Chrome with every
// non-loopback request blocked and recorded, then capture zoom screenshots,
// a screen recording and frame-time measurements.
//
//   GNSS_PLAYWRIGHT_MODULE=~/gnss-poc-88-work/node/node_modules/playwright \
//   node maplibre/evaluate.cjs --bundle ~/gnss-poc-88-work/maplibre-bundle --out ~/gnss-poc-88-work/maplibre-eval
"use strict";
const { spawn, execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return (i > 0 ? process.argv[i + 1] : d).replace(/^~/, os.homedir()); };
const BUNDLE = path.resolve(arg("bundle")), OUT = path.resolve(arg("out")), PORT = Number(arg("port", "8771"));
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const SANDBOX = '(version 1)(allow default)(deny network-outbound)(allow network-outbound (remote ip "localhost:*"))(allow network-outbound (remote unix-socket))';
const BASE = `http://127.0.0.1:${PORT}`;
const VIEWS = [
  ["01-overview-z10", 10, 42.5722, 1.4847], ["02-overview-z11", 11, 42.5722, 1.4847],
  ["03-intermediate-z13-valleys", 13, 42.5500, 1.5300], ["04-intermediate-z13-pessons-lakes", 13, 42.5250, 1.6550],
  ["05-detail-z14-arinsal", 14.3, 42.5722, 1.4847], ["06-detail-z15-andorra-la-vella", 15, 42.5078, 1.5211],
  ["07-detail-z15-coma-pedrosa-trails", 15, 42.5917, 1.4442], ["08-detail-z17-andorra-la-vella-buildings", 17, 42.5089, 1.5340],
];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const res = { measured_at: new Date().toISOString(), external_requests: [], views: [], animations: {} };

async function startServer() {
  const proc = spawn("/usr/bin/sandbox-exec", ["-p", SANDBOX, "python3", path.join(__dirname, "serve.py"), "--bundle", BUNDLE, "--port", String(PORT)], { stdio: "ignore" });
  for (let i = 0; i < 100; i++) { try { await fetch(BASE + "/"); return proc; } catch (_) { await sleep(100); } }
  throw new Error("server did not start");
}
async function page(browser, opts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 880 }, ...opts });
  const p = await ctx.newPage();
  await p.route("**/*", (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === "127.0.0.1" || u.protocol === "data:" || u.protocol === "blob:") return r.continue();
    res.external_requests.push(r.request().url());
    return r.abort();
  });
  return [ctx, p];
}
// Move and wait for this move's own "idle" in one step (bounded), so an event cannot be missed.
const jumpIdle = (p, z, lat, lon) => p.evaluate(([z, lat, lon]) => new Promise((r) => {
  const done = setTimeout(() => r("timeout"), 20000);
  window.map.once("idle", () => { clearTimeout(done); r("idle"); });
  window.map.jumpTo({ zoom: z, center: [lon, lat] });
}), [z, lat, lon]);
async function frames(p, fn) {
  // Run an animation and collect requestAnimationFrame intervals until the map is idle.
  return p.evaluate(async (src) => {
    const map = window.map, d = [];
    let last = performance.now(), on = true;
    const tick = (t) => { d.push(t - last); last = t; if (on) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    const t0 = performance.now();
    await new Function("map", `return (async () => { ${src} })()`)(map);
    await new Promise((r) => { const t = setTimeout(r, 20000); map.once("idle", () => { clearTimeout(t); r(); }); map.triggerRepaint(); });
    on = false;
    const total = performance.now() - t0;
    d.sort((a, b) => a - b);
    const q = (x) => Math.round(d[Math.min(d.length - 1, Math.floor(d.length * x))] * 10) / 10;
    return { duration_ms: Math.round(total), frames: d.length, median_frame_ms: q(0.5), p95_frame_ms: q(0.95), max_frame_ms: q(1), frames_over_33ms: d.filter((x) => x > 33.4).length };
  }, fn);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  // "Complete restart": no server or browser running before this point.
  const t0 = Date.now();
  const server = await startServer();
  res.server_start_ms = Date.now() - t0;
  const browser = await chromium.launch({ channel: "chrome", headless: !process.env.HEADED });
  const [ctx, p] = await page(browser);
  const tNav = Date.now();
  await p.goto(`${BASE}/#11/42.5722/1.4847`);
  await p.waitForFunction(() => window.__poc && window.__poc.firstIdleMs !== null, null, { timeout: 60000 });
  res.cold_load = { navigation_to_first_idle_ms: Date.now() - tNav, ...(await p.evaluate(() => window.__poc)) };
  res.renderer = await p.evaluate(() => { const gl = document.createElement("canvas").getContext("webgl2"); const e = gl.getExtension("WEBGL_debug_renderer_info"); return e ? gl.getParameter(e.UNMASKED_RENDERER_WEBGL) : "unknown"; });
  for (const [name, z, lat, lon] of VIEWS) {
    const t = Date.now();
    const state = await jumpIdle(p, z, lat, lon);
    const ms = Date.now() - t;
    await sleep(350);
    await p.screenshot({ path: path.join(OUT, `${name}.png`) });
    res.views.push({ name, zoom: z, centre: [lat, lon], jump_to_idle_ms: ms, state });
  }
  // Animations (GPU, Apple M3): flyTo across zooms, continuous panning, wheel zoom.
  await jumpIdle(p, 10, 42.5722, 1.4847);
  res.animations.flyTo_z10_to_z16 = await frames(p, 'map.flyTo({ center: [1.534, 42.5089], zoom: 16, speed: 0.8 }); await new Promise((r) => map.once("moveend", r));');
  res.animations.pan_8_steps_z15 = await frames(p, 'for (let i = 0; i < 8; i++) { map.panBy([i % 2 ? -300 : 300, i % 3 ? 120 : -120], { duration: 450 }); await new Promise((r) => map.once("moveend", r)); }');
  res.animations.zoomOut_z16_to_z10 = await frames(p, 'map.easeTo({ zoom: 10, duration: 3000 }); await new Promise((r) => map.once("moveend", r));');
  await ctx.close();

  // Screen recording with real mouse wheel and drag input.
  const [vctx, v] = await page(browser, { viewport: { width: 1280, height: 800 }, recordVideo: { dir: OUT, size: { width: 1280, height: 800 } } });
  await v.goto(`${BASE}/#10.2/42.5600/1.5400`);
  await v.waitForFunction(() => window.__poc && window.__poc.firstIdleMs !== null, null, { timeout: 60000 });
  await sleep(1200);
  await v.mouse.move(700, 480);
  for (let i = 0; i < 40; i++) { await v.mouse.wheel(0, -60); await sleep(60); }          // smooth wheel zoom-in towards Andorra la Vella
  await sleep(1200);
  await v.mouse.down(); for (let i = 0; i < 30; i++) { await v.mouse.move(700 - i * 12, 480 - i * 5); await sleep(16); } await v.mouse.up();
  await sleep(1500);
  await v.evaluate(() => window.map.flyTo({ center: [1.4847, 42.5722], zoom: 14.5, speed: 0.7 }));
  await sleep(6500);
  for (let i = 0; i < 30; i++) { await v.mouse.wheel(0, 70); await sleep(60); }           // wheel zoom-out to overview
  await sleep(2000);
  const videoPath = await v.video().path();
  await vctx.close();
  fs.renameSync(videoPath, path.join(OUT, "zoom-pan-recording.webm"));
  await browser.close();
  server.kill();
  res.storage = Object.fromEntries(fs.readdirSync(path.join(BUNDLE, "tiles")).map((f) => [f, fs.statSync(path.join(BUNDLE, "tiles", f)).size]));
  res.storage.static_runtime_bytes = Number(execSync(`du -sk "${path.join(BUNDLE, "static")}"`).toString().split("\t")[0]) * 1024;
  res.offline = { server: "python3 serve.py under sandbox-exec (deny network-outbound except loopback)", browser: "Chrome, non-loopback requests aborted and recorded", external_requests_attempted: res.external_requests.length };
  fs.writeFileSync(path.join(OUT, "evaluation.json"), JSON.stringify(res, null, 1));
  console.log(JSON.stringify({ cold_load: res.cold_load, renderer: res.renderer, animations: res.animations, external: res.external_requests.length, storage: res.storage }, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
