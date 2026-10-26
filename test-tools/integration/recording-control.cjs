/* Production ownership for fixture-driven recording mutations. Browser tests use
 * the actual page credential; setup without a browser releases its temporary lease. */
"use strict";
exports.recordingPost = async (local, body, browser) => {
  const page = browser?.contexts()[0]?.pages()[0];
  let headers = page
    ? await page.evaluate(() => window.RecordingCapture?.headers() || {})
    : {};
  let lease;
  if (!headers["X-Presentation-Token"]) {
    const response = await fetch(local + "/local/presentation/claim", {
      method: "POST",
      headers: { "Content-Type": "application/json", Origin: local },
      body: "{}",
    });
    if (!response.ok) throw Error(await response.text());
    lease = await response.json();
    headers = {
      "X-Presentation-Token": lease.token,
      "X-Presentation-Epoch": lease.epoch,
    };
  }
  try {
    return await fetch(local + "/local/recording", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...headers },
      body: JSON.stringify(body),
    });
  } finally {
    if (lease)
      await fetch(local + "/local/presentation/close", {
        method: "POST",
        headers: { "Content-Type": "application/json", Origin: local },
        body: JSON.stringify({ token: lease.token, epoch: lease.epoch }),
      });
  }
};
