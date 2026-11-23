"use strict";
// Open the same menus an operator uses before activating an existing action.
exports.menuAction = async (page, id) => {
  // Operator dismissal clears the notification only, never SOS acknowledgement.
  if(await page.locator("#sos-toast-dismiss").isVisible()) await page.locator("#sos-toast-dismiss").click();
  if (id === "open-travel") return exports.menuAction(page, "mode-travel");
  const target = page.locator(`#${id}`);
  // Phone controls opens from the selected party's Details; the legacy entry
  // point stays in the DOM as the dialog authority.
  if (id === "open-control") { await target.evaluate((e) => e.click()); return; }
  // Records/Tools sections are tabs inside the dialog their header button opens.
  const tablist = target.locator("xpath=ancestor::*[@data-tab-opener][1]");
  if (await tablist.count()) {
    if (await target.isVisible()) await target.click();
    // The header button activates its first enabled tab; playback may replace
    // that section with a read-only inspection that has no tab strip.
    else await exports.menuAction(page, await tablist.getAttribute("data-tab-opener"));
    if (await target.isVisible() && (await target.getAttribute("aria-selected")) !== "true") await target.click();
    return;
  }
  const group = target.locator("xpath=ancestor::details[contains(concat(' ',normalize-space(@class),' '),' menu ')][1]");
  if (!(await group.count())) { await target.click(); return; }
  const root = page.locator(".header-menu-root");
  if (await target.evaluate(e => !!e.closest('.header-menu-root'))) {
    if (!(await root.evaluate(e => e.open))) await root.locator(":scope > summary").click();
  }
  if (!(await group.evaluate(e => e.open))) await group.locator(":scope > summary").click();
  await target.click();
};
