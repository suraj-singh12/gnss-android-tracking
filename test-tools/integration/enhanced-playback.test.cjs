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

test("reliably established and settled states reconstruct", async () => {
  const pe = await load();
  assert.equal(pe.suppressionReason({ state: "ready", missing: [], settled: true }), "");
  // known-absent tiles are exactly gated, not suppressed
  assert.equal(
    pe.suppressionReason({
      state: "degraded",
      settled: true,
      missing: [{ product: "vector", z: 10, x: 1, y: 1, kind: "unavailable" }],
    }),
    "",
  );
  assert.equal(
    pe.suppressionReason({
      state: "coverage-incomplete",
      settled: true,
      missing: [{ product: "vector", z: 10, x: 1, y: 1, kind: "unavailable" }],
    }),
    "",
  );
});

test("unconfirmed rendering settlement suppresses reconstruction — geography may never have been requested at capture", async () => {
  const pe = await load();
  // an otherwise clean ready frame captured inside the mutation stability
  // window cannot be trusted to be complete
  assert.match(pe.suppressionReason({ state: "ready", missing: [], settled: false }), /settlement/);
  // absent settlement evidence is conservative: never assume completeness
  assert.match(pe.suppressionReason({ state: "ready", missing: [] }), /settlement/);
  assert.match(pe.suppressionReason(undefined), /settlement/);
  // settled degraded frames still reconstruct: their absences are confirmed outcomes
  assert.match(pe.suppressionReason({ state: "degraded", settled: false, missing: [] }), /settlement/);
});

test("truncation and loading take precedence over settlement wording", async () => {
  const pe = await load();
  assert.match(pe.suppressionReason({ truncated: true, settled: false, state: "ready" }), /truncated/);
  assert.match(pe.suppressionReason({ state: "loading", settled: false }), /still loading/);
});
