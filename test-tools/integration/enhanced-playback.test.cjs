// Focused regression tests for enhanced playback reconstruction honesty (issue #96).
// Pure logic only — browser behaviour is verified live per AGENTS.md.
const test = require("node:test");
const assert = require("node:assert/strict");

async function load() {
  await import("../../command/web/playback-enhanced.js");
  return globalThis.PlaybackEnhanced;
}

test("truncated availability evidence suppresses reconstruction", async () => {
  const pe = await load();
  assert.match(pe.suppressionReason({ truncated: true, state: "ready" }), /truncated/);
});

test("failed availability evidence suppresses reconstruction", async () => {
  const pe = await load();
  assert.match(pe.suppressionReason({ state: "failed" }), /uncertain/);
});

test("tiles still loading at capture suppress reconstruction — later-loaded geography is never cosmetic", async () => {
  const pe = await load();
  assert.match(pe.suppressionReason({ state: "loading" }), /still loading/);
  // a loading entry inside an otherwise degraded frame is still uncertain
  assert.match(
    pe.suppressionReason({
      state: "degraded",
      missing: [
        { product: "vector", z: 10, x: 1, y: 1, kind: "unavailable" },
        { product: "vector", z: 10, x: 2, y: 1, kind: "loading" },
      ],
    }),
    /still loading/,
  );
});

test("reliably established states reconstruct", async () => {
  const pe = await load();
  assert.equal(pe.suppressionReason({ state: "ready", missing: [] }), "");
  // known-absent tiles are exactly gated, not suppressed
  assert.equal(
    pe.suppressionReason({
      state: "degraded",
      missing: [{ product: "vector", z: 10, x: 1, y: 1, kind: "unavailable" }],
    }),
    "",
  );
  assert.equal(
    pe.suppressionReason({
      state: "coverage-incomplete",
      missing: [{ product: "vector", z: 10, x: 1, y: 1, kind: "unavailable" }],
    }),
    "",
  );
  assert.equal(pe.suppressionReason(undefined), "");
});
