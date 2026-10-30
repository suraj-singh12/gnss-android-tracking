/* Component compatibility fixture for #27's omitted SVG clip definitions.
 * Main recording-playback.cjs separately verifies real persisted evidence/assets. */
"use strict";
const assert = require("node:assert/strict"),
  fs = require("node:fs"),
  path = require("node:path"),
  os = require("node:os"),
  net = require("node:net"),
  { spawn } = require("node:child_process"),
  { once } = require("node:events"),
  { createHash } = require("node:crypto");
const { chromium } = require(
  process.env.GNSS_PLAYWRIGHT_MODULE || "playwright",
);
async function port() {
  const s = net.createServer().listen(0, "127.0.0.1");
  await once(s, "listening");
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
(async () => {
  let command, browser;
  try {
    const local = `http://127.0.0.1:${await port()}`,
      phone = `http://127.0.0.1:${await port()}`,
      work = fs.mkdtempSync(path.join(os.tmpdir(), "gnss-legacy-clips-"));
    command = spawn(
      process.env.GNSS_COMMAND_BINARY,
      [
        "-db",
        path.join(work, "command.sqlite"),
        "-ingest-listen",
        phone.slice(7),
        "-dashboard-listen",
        local.slice(7),
      ],
      { stdio: "ignore" },
    );
    for (let n = 0; n < 100; n++) {
      try {
        if ((await fetch(local + "/local/state")).ok) break;
      } catch {}
      await new Promise((r) => setTimeout(r, 100));
    }
    browser = await chromium.launch({
      headless: true,
      ...(process.env.GNSS_CHROMIUM
        ? { executablePath: process.env.GNSS_CHROMIUM }
        : {}),
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    // Expose the real pure presentation helper only in this instrumented test response.
    const source = fs.readFileSync(
      path.join(__dirname, "../../command/web/playback.js"),
      "utf8",
    );
    assert(source.includes("  window.HistoricalPlayback = {"));
    await page.route("**/playback.js", (route) =>
      route.fulfill({
        contentType: "application/javascript",
        body: source.replace(
          "  window.HistoricalPlayback = {",
          '  window.__clipFixture = { restore:restorePinnedClips, setSession:()=>session={metadata:{id:"compatibility-fixture"}} };\n  window.HistoricalPlayback = {',
        ),
      }),
    );
    const assets = [
      { plan: { request: { lon: 179.95 } } },
      { chunk_key: "legacy.grid:one", core_bounds: [-180, 28, -179.9, 28.1] },
    ].map((value) => {
      const body = JSON.stringify(value);
      return { body, hash: createHash("sha256").update(body).digest("hex") };
    });
    await page.route("**/local/presentation/saved?**", async (route) => {
      const hash = new URL(route.request().url()).searchParams.get("asset"),
        asset = assets.find((a) => a.hash === hash);
      assert(asset);
      await route.fulfill({
        body: asset.body,
        contentType: "application/json",
        headers: { "X-Presentation-Asset": hash },
      });
    });
    await page.goto(local);
    const result = await page.evaluate(
      async (hashes) => {
        window.__clipFixture.setSession();
        const [cx, cy] = GeoMap.project(179.95, 28.05),
          common = {
            width: 800,
            height: 600,
            viewport: { cx, cy, scale: 0.01 },
            map_svg:
              '<g data-layer="contours" clip-path="url(#clip-legacy.grid:one)"><path d="M0,0L1,1"/></g>',
          };
        const checkpoint = { common, assets: hashes };
        await window.__clipFixture.restore(checkpoint);
        const doc = new DOMParser().parseFromString(
            '<svg xmlns="http://www.w3.org/2000/svg">' +
              checkpoint.common.map_svg +
              "</svg>",
            "image/svg+xml",
          ),
          rect = doc.querySelector("clipPath rect");
        const missing = {
          common: {
            ...common,
            map_svg:
              '<g data-layer="contours" clip-path="url(#clip-absent)"><path d="M0,0L1,1"/></g>',
          },
          assets: [],
        };
        await window.__clipFixture.restore(missing);
        return {
          restored: !!rect,
          x: rect ? Number(rect.getAttribute("x")) : null,
          width: rect ? Number(rect.getAttribute("width")) : null,
          warning: missing.asset_warnings?.[0],
          unsupportedLayerRemoved: !missing.common.map_svg.includes(
            'data-layer="contours"',
          ),
        };
      },
      assets.map((a) => a.hash),
    );
    assert(
      result.restored,
      "restore actual SVG clip via the shared radius helper",
    );
    assert(
      result.x > 400 && result.x < 500,
      "use pinned dateline map wrap, not current live map",
    );
    assert(result.width > 100 && result.width < 120);
    assert(result.warning.includes("legacy recorded map clipping"));
    assert(result.unsupportedLayerRemoved);
    console.log(
      "PASS legacy presentation compatibility: pinned core clipping, dateline wrap, dotted/colon IDs and labelled unavailable layers.",
    );
  } finally {
    if (browser) await browser.close();
    if (command) command.kill();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
