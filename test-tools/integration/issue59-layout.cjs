"use strict";
// Run against a running Command instance: GNSS_COMMAND_URL=http://127.0.0.1:8081 node ...
const assert = require("node:assert/strict");
const {chromium} = require(process.env.GNSS_PLAYWRIGHT_MODULE || "playwright");
(async () => {
  const browser = await chromium.launch({executablePath: process.env.GNSS_CHROMIUM || undefined, headless: true, args:["--no-sandbox"]});
  try {
    for (const [width,height] of [[390,844],[960,720],[1280,720],[1440,900],[1920,1080]]) {
      const page = await browser.newPage({viewport:{width,height}});
      const errors=[]; page.on("pageerror", e => errors.push(e.message));
      await page.goto(process.env.GNSS_COMMAND_URL || "http://127.0.0.1:8081");
      for (const theme of ["day","night"]) {
        await page.evaluate(t => localStorage.setItem("gnss-appearance",t), theme); await page.reload();
        await page.waitForSelector("#toggle-parties");
        const geometry = await page.evaluate(() => {
          const rect = selector => { const r=document.querySelector(selector).getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
          return {header:rect("header"),rail:rect(".recording-bar"),map:rect(".canvas"),sos:rect("#open-sos"),audio:rect("#sos-audio"), connection:rect("#connection"), menu:rect(".header-menu-root > summary")};
        });
        assert.equal(geometry.header.height,60); assert.equal(geometry.rail.height,48);
        assert(geometry.sos.right <= width && geometry.audio.right <= width);
        if (width <= 600) assert(geometry.connection.right <= geometry.menu.x, JSON.stringify(geometry));
        assert(geometry.map.width >= width * .6, JSON.stringify(geometry));
        const parties = page.locator("#toggle-parties");
        const before = await parties.getAttribute("aria-expanded");
        await parties.click();
        assert.notEqual(await parties.getAttribute("aria-expanded"), before);
        await parties.click();
        const root=page.locator(".header-menu-root");
        if (!(await root.evaluate(e=>e.open))) await root.locator(":scope > summary").click();
        const records=page.locator(".menu-groups > details").nth(1);
        await records.locator(":scope > summary").click();
        assert(await page.locator("#open-library").isVisible());
        await page.keyboard.press("Escape");
        assert.equal(await records.evaluate(e=>e.open),false);
        assert.equal(await records.locator(":scope > summary").evaluate(e=>e===document.activeElement),true);
        if (width <= 1100) await page.keyboard.press("Escape");
        await page.locator(".map-tools > summary").click();
        assert(await page.locator("#fit").isVisible());
        await page.keyboard.press("Escape");
      }
      assert.deepEqual(errors,[]); await page.close();
    }
  } finally { await browser.close(); }
})().catch(e => {console.error(e);process.exitCode=1});
