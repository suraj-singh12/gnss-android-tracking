#!/usr/bin/env node
// Side-by-side: actual unmodified Command (captures from capture.cjs) vs this MapLibre POC
// at the identical centre and scale. MapLibre zoom = raster (256 px) zoom - 1.
//   GNSS_PLAYWRIGHT_MODULE=... node maplibre/compare.cjs --command ~/gnss-poc-88-work/captures-v2 \
//     --url http://127.0.0.1:8770 --out ~/gnss-poc-88-work/maplibre-compare
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return (i > 0 ? process.argv[i + 1] : d).replace(/^~/, os.homedir()); };
const CMD = path.resolve(arg("command")), URL0 = arg("url", "http://127.0.0.1:8770"), OUT = path.resolve(arg("out"));
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const VIEWS = [ // capture.cjs ids and raster zooms
  ["overview", 11, 42.5722, 1.4847, "Regional overview"], ["valley-terrain", 13, 42.56, 1.55, "Valleys, roads and relief"],
  ["arinsal-detail", 14, 42.5722, 1.4847, "Arinsal village and trails"], ["coma-pedrosa-detail", 14, 42.5917, 1.4442, "Summits, lakes and trails"],
  ["pessons-lakes", 14, 42.5135, 1.662, "Lakes, forest and land cover"], ["andorra-la-vella-detail", 15, 42.5078, 1.5211, "Town: roads, names, buildings"],
];
const W = 860, H = 640;
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ channel: "chrome" });
  const p = await b.newPage({ viewport: { width: W, height: H } });
  await p.route("**/*", (r) => (new URL(r.request().url()).hostname === "127.0.0.1" ? r.continue() : r.abort()));
  await p.goto(`${URL0}/#10/42.57/1.48`);
  await p.waitForFunction(() => window.__poc && window.__poc.firstIdleMs !== null, null, { timeout: 60000 });
  await p.addStyleTag({ content: "#badge{display:none}" });
  const shots = {};
  for (const [id, z, lat, lon] of VIEWS) {
    await p.evaluate(([z, lat, lon]) => new Promise((r) => { window.map.once("idle", r); window.map.jumpTo({ zoom: z - 1, center: [lon, lat] }); }), [z, lat, lon]);
    await new Promise((r) => setTimeout(r, 400));
    shots[id] = (await p.screenshot()).toString("base64");
  }
  const c = await b.newPage({ viewport: { width: W * 2 + 12, height: H + 64 } });
  for (const [id, z, , , title] of VIEWS) {
    const cmd = fs.readFileSync(path.join(CMD, `command-enhanced-${id}.png`)).toString("base64");
    await c.setContent(`<style>body{margin:0;font:600 15px -apple-system,system-ui,sans-serif;background:#24343a;color:#fff}
      .t{height:30px;line-height:30px;padding:0 12px;font-size:16px}.row{display:flex;gap:12px}.h{height:30px;line-height:30px;padding:0 10px;font-size:13px}
      .a{background:#216f56}.b{background:#3b5bab}.m{width:${W}px;height:${H}px;background-repeat:no-repeat}</style>
      <div class="t">${title} · same centre and scale (raster z${z} = MapLibre z${z - 1})</div><div class="row">
      <div><div class="h a">ACTUAL · current GNSS Command renderer</div><div class="m" style="background-image:url(data:image/png;base64,${cmd});background-position:${W / 2 - 464}px ${H / 2 - 397}px"></div></div>
      <div><div class="h b">MapLibre GL JS offline POC (same Arinsal data, not integrated)</div><div class="m" style="background-image:url(data:image/png;base64,${shots[id]})"></div></div></div>`);
    await c.screenshot({ path: path.join(OUT, `${id}.jpg`), type: "jpeg", quality: 72 });
  }
  await b.close();
  console.log("compared", VIEWS.length);
})();
