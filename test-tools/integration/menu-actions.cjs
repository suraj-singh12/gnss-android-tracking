"use strict";
// Open the same menus an operator uses before activating an existing action.
exports.menuAction = async (page, id) => {
  // Operator dismissal clears the notification only, never SOS acknowledgement.
  if(await page.locator("#sos-toast-dismiss").isVisible()) await page.locator("#sos-toast-dismiss").click();
  const target = page.locator(`#${id}`);
  const group = target.locator("xpath=ancestor::details[contains(concat(' ',normalize-space(@class),' '),' menu ')][1]");
  if (!(await group.count())) { await target.click(); return; }
  const root = page.locator(".header-menu-root");
  if (await target.evaluate(e => !!e.closest('.header-menu-root'))) {
    if (!(await root.evaluate(e => e.open))) await root.locator(":scope > summary").click();
  }
  if (!(await group.evaluate(e => e.open))) await group.locator(":scope > summary").click();
  await target.click();
};
