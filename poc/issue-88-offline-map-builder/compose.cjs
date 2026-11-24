#!/usr/bin/env node
// Compose labelled side-by-side comparisons from capture.cjs output.
// Left: actual unmodified Command. Right: POC reference renderer (experimental).
// Both crops are centred on the identical WGS84 centre at the identical Web
// Mercator scale (Command viewport scale = 256*2^z / (2*pi*R) px per metre).
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1].replace(/^~/, os.homedir()) : d; };
const IN = path.resolve(arg("in")), OUT = path.resolve(arg("out"));
const { chromium } = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
const W = 860, H = 640;
// Map centres inside each capture: Command clip 928x794 (map cell), viewer 1200x800 (44 px header).
const CENTER = { command: [464, 397], viewer: [600, 422] };
const PAIRS = [
  ["01-overview-z11", "overview", "topo", "Regional overview · z11 · whole 20 km area"],
  ["02-arinsal-topo-z14", "arinsal-detail", "topo", "Detailed topographic · Arinsal · z14"],
  ["03-andorra-la-vella-z15", "andorra-la-vella-detail", "topo", "Roads, buildings and labels · Andorra la Vella · z15"],
  ["04-coma-pedrosa-z14", "coma-pedrosa-detail", "topo", "Summits, trails and lakes · Coma Pedrosa · z14"],
  ["05-pessons-lakes-z14", "pessons-lakes", "topo", "Lakes, forest and land cover · Pessons · z14"],
  ["06-valley-terrain-z13", "valley-terrain", "terrain", "Terrain, contours and hillshade · z13"],
  ["07-arinsal-satellite-z14", "arinsal-detail", "satellite", "Satellite · Arinsal · z14"],
  ["08-andorra-la-vella-hybrid-z15", "andorra-la-vella-detail", "hybrid", "Hybrid · Andorra la Vella · z15"],
  ["09-pessons-hybrid-z14", "pessons-lakes", "hybrid", "Hybrid · Pessons lakes · z14"],
];
const MODE = { topo: "topographic", terrain: "terrain", satellite: "satellite", hybrid: "hybrid" };
const uri = (f) => "data:image/png;base64," + fs.readFileSync(f).toString("base64");
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W * 2 + 12, height: H + 64 } });
  for (const [name, view, mode, title] of PAIRS) {
    const cmd = path.join(IN, `command-enhanced-${view}.png`), ref = path.join(IN, `viewer-${view}-${mode}.png`);
    if (!fs.existsSync(cmd) || !fs.existsSync(ref)) { console.error("missing", name); continue; }
    const pos = (c) => `${W / 2 - c[0]}px ${H / 2 - c[1]}px`;
    await page.setContent(`<style>body{margin:0;font:600 15px -apple-system,system-ui,sans-serif;background:#24343a;color:#fff}
      .t{height:30px;line-height:30px;padding:0 12px;font-size:16px}.row{display:flex;gap:12px}
      .c{width:${W}px}.h{height:30px;line-height:30px;padding:0 10px;font-size:13px}.a{background:#216f56}.b{background:#9b681d}
      .m{width:${W}px;height:${H}px;background-repeat:no-repeat}</style>
      <div class="t">${title}</div><div class="row">
      <div class="c"><div class="h a">ACTUAL · unmodified GNSS Command (current renderer, same enhanced data)</div><div class="m" style="background-image:url(${uri(cmd)});background-position:${pos(CENTER.command)}"></div></div>
      <div class="c"><div class="h b">EXPERIMENTAL · POC reference renderer, ${MODE[mode]} (not Command)</div><div class="m" style="background-image:url(${uri(ref)});background-position:${pos(CENTER.viewer)}"></div></div></div>`);
    await page.screenshot({ path: path.join(OUT, name + ".jpg"), type: "jpeg", quality: 70 });
  }
  await browser.close();
  console.log("composed", fs.readdirSync(OUT).length);
})();
